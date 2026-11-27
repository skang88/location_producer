/*
 * ESP32 ZUPT / PDR BLE Sensor Node Firmware for Rescuer Location Producer
 *
 * Hardware:
 *   - ESP32 Dev Module (I2C: SDA=21, SCL=22)
 *   - IMU: Bosch BMI160 (6-axis Accel + Gyro, I2C 0x68 / 0x69)
 *   - Barometer: Bosch BMP388 (Pressure + Temp, I2C 0x76 / 0x77)
 *
 * Required Library (Arduino IDE Library Manager):
 *   - Adafruit BMP3XX Library (by Adafruit)
 *   - (BMI160 uses direct Wire I2C — no external library required)
 *
 * Communication: BLE Nordic UART Service (6E400001) @ ~50Hz (20ms)
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>
#include <Adafruit_BMP3XX.h>

// ==========================================
// BLE Nordic UART Service UUIDs
// ==========================================
#define SERVICE_UUID           "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define CHARACTERISTIC_UUID_TX "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// ==========================================
// BMI160 I2C Registers & Constants
// ==========================================
#define BMI160_REG_CHIP_ID   0x00
#define BMI160_CHIP_ID_VAL   0xD1
#define BMI160_REG_DATA_GYR  0x0C // 12 bytes: GYR_X..Z (6B) + ACC_X..Z (6B)
#define BMI160_REG_ACC_CONF  0x40
#define BMI160_REG_ACC_RANGE 0x41
#define BMI160_REG_GYR_CONF  0x42
#define BMI160_REG_GYR_RANGE 0x43
#define BMI160_REG_CMD       0x7E

// Scale factors:
// Accel ±4G -> 8192 LSB/g -> m/s^2
static const float ACC_SCALE = 9.80665f / 8192.0f;
// Gyro ±500 dps -> 65.536 LSB/(deg/s) -> rad/s
static const float GYR_SCALE = (PI / 180.0f) / 65.536f;

BLEServer *pServer = NULL;
BLECharacteristic *pTxCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

uint8_t bmi160Addr = 0;
bool bmi160Ready = false;

Adafruit_BMP3XX bmp;
bool bmpReady = false;

// Gyro zero-rate bias (calibrated on boot while stationary)
float gyroBiasX = 0.0f, gyroBiasY = 0.0f, gyroBiasZ = 0.0f;

unsigned long lastPacketTime = 0;

class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println(">>> [ESP32 BLE] Smart Phone Connected!");
    }
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">>> [ESP32 BLE] Smart Phone Disconnected!");
    }
};

uint8_t i2cReadByte(uint8_t addr, uint8_t reg) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return 0xFF;
  Wire.requestFrom(addr, (uint8_t)1);
  return Wire.available() ? Wire.read() : 0xFF;
}

void i2cWriteByte(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

bool initBMI160() {
  uint8_t candidates[2] = {0x68, 0x69};
  for (int i = 0; i < 2; i++) {
    uint8_t chipId = i2cReadByte(candidates[i], BMI160_REG_CHIP_ID);
    if (chipId == BMI160_CHIP_ID_VAL) {
      bmi160Addr = candidates[i];
      break;
    }
  }
  if (bmi160Addr == 0) {
    Serial.println("[ERROR] BMI160 not found at 0x68 or 0x69!");
    return false;
  }

  // Soft reset
  i2cWriteByte(bmi160Addr, BMI160_REG_CMD, 0xB6);
  delay(100);

  // Set Accel & Gyro to Normal Mode
  i2cWriteByte(bmi160Addr, BMI160_REG_CMD, 0x11); // Accel normal
  delay(50);
  i2cWriteByte(bmi160Addr, BMI160_REG_CMD, 0x15); // Gyro normal
  delay(80);

  // Configure Accel: 100Hz ODR, normal bandwidth (0x28), ±4G range (0x05)
  i2cWriteByte(bmi160Addr, BMI160_REG_ACC_CONF, 0x28);
  i2cWriteByte(bmi160Addr, BMI160_REG_ACC_RANGE, 0x05);

  // Configure Gyro: 100Hz ODR, normal bandwidth (0x28), ±500dps range (0x02)
  i2cWriteByte(bmi160Addr, BMI160_REG_GYR_CONF, 0x28);
  i2cWriteByte(bmi160Addr, BMI160_REG_GYR_RANGE, 0x02);
  delay(50);

  Serial.printf("[OK] BMI160 initialized at I2C 0x%02X\n", bmi160Addr);
  return true;
}

bool readBMI160Raw(float &ax, float &ay, float &az, float &gx, float &gy, float &gz) {
  if (!bmi160Ready) return false;
  Wire.beginTransmission(bmi160Addr);
  Wire.write(BMI160_REG_DATA_GYR);
  if (Wire.endTransmission(false) != 0) return false;

  if (Wire.requestFrom(bmi160Addr, (uint8_t)12) != 12) return false;

  int16_t rgx = (int16_t)(Wire.read() | (Wire.read() << 8));
  int16_t rgy = (int16_t)(Wire.read() | (Wire.read() << 8));
  int16_t rgz = (int16_t)(Wire.read() | (Wire.read() << 8));
  int16_t rax = (int16_t)(Wire.read() | (Wire.read() << 8));
  int16_t ray = (int16_t)(Wire.read() | (Wire.read() << 8));
  int16_t raz = (int16_t)(Wire.read() | (Wire.read() << 8));

  gx = rgx * GYR_SCALE - gyroBiasX;
  gy = rgy * GYR_SCALE - gyroBiasY;
  gz = rgz * GYR_SCALE - gyroBiasZ;
  ax = rax * ACC_SCALE;
  ay = ray * ACC_SCALE;
  az = raz * ACC_SCALE;
  return true;
}

void calibrateGyroBias() {
  if (!bmi160Ready) return;
  Serial.println(">>> Calibrating BMI160 Gyro bias (keep board still)...");
  double sumX = 0, sumY = 0, sumZ = 0;
  int samples = 100;
  for (int i = 0; i < samples; i++) {
    float ax, ay, az, gx, gy, gz;
    if (readBMI160Raw(ax, ay, az, gx, gy, gz)) {
      sumX += gx;
      sumY += gy;
      sumZ += gz;
    }
    delay(10);
  }
  gyroBiasX = (float)(sumX / samples);
  gyroBiasY = (float)(sumY / samples);
  gyroBiasZ = (float)(sumZ / samples);
  Serial.printf("[OK] Gyro bias: (%.4f, %.4f, %.4f) rad/s\n", gyroBiasX, gyroBiasY, gyroBiasZ);
}

bool initBMP388() {
  if (bmp.begin_I2C(0x76) || bmp.begin_I2C(0x77)) {
    bmp.setTemperatureOversampling(BMP3_OVERSAMPLING_2X);
    bmp.setPressureOversampling(BMP3_OVERSAMPLING_8X);
    bmp.setIIRFilterCoeff(BMP3_IIR_FILTER_COEFF_3);
    bmp.setOutputDataRate(BMP3_ODR_50_HZ);
    Serial.println("[OK] BMP388 initialized");
    return true;
  }
  Serial.println("[WARN] BMP388 not found at 0x76 or 0x77 (phone barometer will be used)");
  return false;
}

void setup() {
  Serial.begin(115200);
  Wire.begin(); // Default ESP32 I2C: SDA=21, SCL=22 (change to Wire.begin(SDA, SCL) if custom pins)
  Wire.setClock(400000);

  Serial.println("==================================================");
  Serial.println(" ESP32 ZUPT/PDR BLE Sensor Node (BMI160 + BMP388) ");
  Serial.println("==================================================");

  bmi160Ready = initBMI160();
  calibrateGyroBias();
  bmpReady = initBMP388();

  // Initialize BLE with 512-byte MTU support
  BLEDevice::init("ESP32_FOOT_IMU");
  BLEDevice::setMTU(512);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);
  pTxCharacteristic = pService->createCharacteristic(
                        CHARACTERISTIC_UUID_TX,
                        BLECharacteristic::PROPERTY_NOTIFY
                      );
  pTxCharacteristic->addDescriptor(new BLE2902());
  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println(">>> BLE Advertising Started: [ESP32_FOOT_IMU]");
}

void loop() {
  if (!deviceConnected && oldDeviceConnected) {
    delay(500);
    pServer->startAdvertising();
    Serial.println(">>> Restarted BLE Advertising...");
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  unsigned long now = millis();
  if (now - lastPacketTime >= 20) { // ~50Hz
    lastPacketTime = now;

    float ax = 0.0f, ay = 0.0f, az = 9.81f;
    float gx = 0.0f, gy = 0.0f, gz = 0.0f;
    readBMI160Raw(ax, ay, az, gx, gy, gz);

    float pressure = 0.0f;
    if (bmpReady && bmp.performReading()) {
      pressure = (float)(bmp.pressure / 100.0); // Pa -> hPa
    }

    // Note: BMI160 is a 6-axis IMU (no magnetometer), so "yaw" is omitted
    // and Android app fuses phone compass yaw with ESP32 gyro/accel.
    char payload[256];
    if (pressure > 300.0f) {
      snprintf(payload, sizeof(payload),
        "{\"accel\":{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f},\"gyro\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},\"barometer\":{\"pressure\":%.2f}}",
        ax, ay, az, gx, gy, gz, pressure
      );
    } else {
      snprintf(payload, sizeof(payload),
        "{\"accel\":{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f},\"gyro\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f}}",
        ax, ay, az, gx, gy, gz
      );
    }

    if (deviceConnected) {
      pTxCharacteristic->setValue((uint8_t*)payload, strlen(payload));
      pTxCharacteristic->notify();
    }
  }
}
