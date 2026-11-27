/*
 * ESP32 ZUPT / PDR BLE Sensor Node Firmware for Rescuer Location Producer
 *
 * Hardware:
 *   - ESP32 Dev Module (I2C: SDA=21, SCL=22)
 *   - IMU: Bosch BMI160 (6-axis Accel + Gyro, I2C 0x68 / 0x69)
 *   - Barometer: Bosch BMP388 (Pressure + Temp, I2C 0x76 / 0x77)
 *
 * No external libraries required! Uses built-in ESP32 BLE + Wire (I2C).
 *
 * Communication: BLE Nordic UART Service (6E400001) @ ~50Hz (20ms)
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Wire.h>
#include <math.h>

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

// ==========================================
// BMP388 I2C Registers & Constants
// ==========================================
#define BMP388_REG_CHIP_ID   0x00 // 0x50 for BMP388, 0x60 for BMP390
#define BMP388_REG_DATA      0x04 // 6 bytes: P[23:0], T[23:0]
#define BMP388_REG_PWR_CTRL  0x1B
#define BMP388_REG_OSR       0x1C
#define BMP388_REG_ODR       0x1D
#define BMP388_REG_CONFIG    0x1F
#define BMP388_REG_CALIB     0x31 // 21 bytes NVM calibration data
#define BMP388_REG_CMD       0x7E

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

uint8_t bmp388Addr = 0;
bool bmp388Ready = false;

int activeSda = -1;
int activeScl = -1;

// BMP388 floating-point calibration coefficients
struct Bmp388Calib {
  double t1, t2, t3;
  double p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11;
} bmpCalib;

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

bool i2cReadBytes(uint8_t addr, uint8_t reg, uint8_t *buf, uint8_t len) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(addr, len) != len) return false;
  for (uint8_t i = 0; i < len; i++) {
    buf[i] = Wire.read();
  }
  return true;
}

void i2cWriteByte(uint8_t addr, uint8_t reg, uint8_t val) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(val);
  Wire.endTransmission();
}

// ==========================================
// BMI160 Driver
// ==========================================
bool initBMI160() {
  uint8_t candidates[2] = {0x68, 0x69};
  for (int i = 0; i < 2; i++) {
    // BMI160 requires a dummy I2C read (reg 0x7F) after power-on to switch from SPI to I2C mode
    i2cReadByte(candidates[i], 0x7F);
    delay(5);
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
  uint8_t buf[12];
  if (!i2cReadBytes(bmi160Addr, BMI160_REG_DATA_GYR, buf, 12)) return false;

  int16_t rgx = (int16_t)(buf[0] | (buf[1] << 8));
  int16_t rgy = (int16_t)(buf[2] | (buf[3] << 8));
  int16_t rgz = (int16_t)(buf[4] | (buf[5] << 8));
  int16_t rax = (int16_t)(buf[6] | (buf[7] << 8));
  int16_t ray = (int16_t)(buf[8] | (buf[9] << 8));
  int16_t raz = (int16_t)(buf[10] | (buf[11] << 8));

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

// ==========================================
// BMP388 Direct I2C Driver (No Library Needed)
// ==========================================
bool initBMP388() {
  uint8_t candidates[2] = {0x76, 0x77};
  for (int i = 0; i < 2; i++) {
    uint8_t chipId = i2cReadByte(candidates[i], BMP388_REG_CHIP_ID);
    if (chipId == 0x50 || chipId == 0x60) {
      bmp388Addr = candidates[i];
      break;
    }
  }
  if (bmp388Addr == 0) {
    Serial.println("[WARN] BMP388 not found at 0x76 or 0x77 (phone barometer will be used)");
    return false;
  }

  // Soft reset
  i2cWriteByte(bmp388Addr, BMP388_REG_CMD, 0xB6);
  delay(20);

  // Read 21-byte NVM calibration data
  uint8_t c[21];
  if (!i2cReadBytes(bmp388Addr, BMP388_REG_CALIB, c, 21)) {
    Serial.println("[WARN] Failed to read BMP388 calibration");
    return false;
  }

  uint16_t nvm_t1 = (uint16_t)(c[0] | (c[1] << 8));
  uint16_t nvm_t2 = (uint16_t)(c[2] | (c[3] << 8));
  int8_t   nvm_t3 = (int8_t)c[4];
  int16_t  nvm_p1 = (int16_t)(c[5] | (c[6] << 8));
  int16_t  nvm_p2 = (int16_t)(c[7] | (c[8] << 8));
  int8_t   nvm_p3 = (int8_t)c[9];
  int8_t   nvm_p4 = (int8_t)c[10];
  uint16_t nvm_p5 = (uint16_t)(c[11] | (c[12] << 8));
  uint16_t nvm_p6 = (uint16_t)(c[13] | (c[14] << 8));
  int8_t   nvm_p7 = (int8_t)c[15];
  int8_t   nvm_p8 = (int8_t)c[16];
  int16_t  nvm_p9 = (int16_t)(c[17] | (c[18] << 8));
  int8_t   nvm_p10 = (int8_t)c[19];
  int8_t   nvm_p11 = (int8_t)c[20];

  bmpCalib.t1  = (double)nvm_t1 * 256.0;
  bmpCalib.t2  = (double)nvm_t2 / 1073741824.0;          // 2^30
  bmpCalib.t3  = (double)nvm_t3 / 281474976710656.0;     // 2^48
  bmpCalib.p1  = ((double)nvm_p1 - 16384.0) / 1048576.0; // 2^20
  bmpCalib.p2  = ((double)nvm_p2 - 16384.0) / 536870912.0; // 2^29
  bmpCalib.p3  = (double)nvm_p3 / 4294967296.0;          // 2^32
  bmpCalib.p4  = (double)nvm_p4 / 137438953472.0;        // 2^37
  bmpCalib.p5  = (double)nvm_p5 * 8.0;                   // 2^-3
  bmpCalib.p6  = (double)nvm_p6 / 64.0;                  // 2^6
  bmpCalib.p7  = (double)nvm_p7 / 256.0;                 // 2^8
  bmpCalib.p8  = (double)nvm_p8 / 32768.0;               // 2^15
  bmpCalib.p9  = (double)nvm_p9 / 281474976710656.0;     // 2^48
  bmpCalib.p10 = (double)nvm_p10 / 281474976710656.0;    // 2^48
  bmpCalib.p11 = (double)nvm_p11 / pow(2.0, 65.0);

  // Configure BMP388: OSR press=8x (0x03), ODR=50Hz (0x02), IIR=3 (0x02), Normal mode (0x33)
  i2cWriteByte(bmp388Addr, BMP388_REG_OSR, 0x03);
  i2cWriteByte(bmp388Addr, BMP388_REG_ODR, 0x02);
  i2cWriteByte(bmp388Addr, BMP388_REG_CONFIG, 0x02);
  i2cWriteByte(bmp388Addr, BMP388_REG_PWR_CTRL, 0x33);
  delay(20);

  Serial.printf("[OK] BMP388 initialized at I2C 0x%02X\n", bmp388Addr);
  return true;
}

float readBMP388PressureHpa() {
  if (!bmp388Ready) return 0.0f;
  uint8_t buf[6];
  if (!i2cReadBytes(bmp388Addr, BMP388_REG_DATA, buf, 6)) return 0.0f;

  uint32_t uncomp_press = (uint32_t)buf[0] | ((uint32_t)buf[1] << 8) | ((uint32_t)buf[2] << 16);
  uint32_t uncomp_temp  = (uint32_t)buf[3] | ((uint32_t)buf[4] << 8) | ((uint32_t)buf[5] << 16);

  // Compensate temperature (Bosch datasheet formula)
  double pd1 = (double)uncomp_temp - bmpCalib.t1;
  double pd2 = pd1 * bmpCalib.t2;
  double t_lin = pd2 + (pd1 * pd1) * bmpCalib.t3;

  // Compensate pressure (Bosch datasheet formula)
  double pd_1 = bmpCalib.p6 * t_lin;
  double pd_2 = bmpCalib.p7 * (t_lin * t_lin);
  double pd_3 = bmpCalib.p8 * (t_lin * t_lin * t_lin);
  double po1  = bmpCalib.p5 + pd_1 + pd_2 + pd_3;

  double pd_4 = bmpCalib.p2 * t_lin;
  double pd_5 = bmpCalib.p3 * (t_lin * t_lin);
  double pd_6 = bmpCalib.p4 * (t_lin * t_lin * t_lin);
  double po2  = (double)uncomp_press * (bmpCalib.p1 + pd_4 + pd_5 + pd_6);

  double p2   = (double)uncomp_press * (double)uncomp_press;
  double pd_7 = bmpCalib.p9 + bmpCalib.p10 * t_lin;
  double pd_8 = p2 * pd_7;
  double pd_9 = pd_8 + (p2 * (double)uncomp_press) * bmpCalib.p11;

  double comp_press_pa = po1 + po2 + pd_9;
  return (float)(comp_press_pa / 100.0); // Pa -> hPa
}

void autoDetectI2CPins() {
  // Safe GPIO candidates for ESP32-C6 (avoiding USB D-/D+ pins 12, 13)
  const int candidates[][2] = {
    {SDA, SCL},
    {6, 7}, {7, 6},
    {4, 5}, {5, 4},
    {21, 22}, {22, 21},
    {18, 19}, {19, 18},
    {2, 3}, {3, 2},
    {8, 9}, {9, 8},
    {10, 11}, {11, 10},
    {20, 21}, {22, 23}
  };
  const int numCandidates = sizeof(candidates) / sizeof(candidates[0]);

  for (int i = 0; i < numCandidates; i++) {
    int sda = candidates[i][0];
    int scl = candidates[i][1];
    if (sda == 12 || sda == 13 || scl == 12 || scl == 13) continue;

    Wire.end();
    Wire.begin(sda, scl);
    Wire.setClock(100000);
    delay(10);

    // Trigger BMI160 I2C wake-up (dummy read on 0x7F) before checking CHIP_ID
    i2cReadByte(0x68, 0x7F);
    i2cReadByte(0x69, 0x7F);
    delay(2);

    bool foundImu = (i2cReadByte(0x68, BMI160_REG_CHIP_ID) == BMI160_CHIP_ID_VAL) ||
                    (i2cReadByte(0x69, BMI160_REG_CHIP_ID) == BMI160_CHIP_ID_VAL);
    uint8_t bmpId76 = i2cReadByte(0x76, BMP388_REG_CHIP_ID);
    uint8_t bmpId77 = i2cReadByte(0x77, BMP388_REG_CHIP_ID);
    bool foundBmp = (bmpId76 == 0x50 || bmpId76 == 0x60 || bmpId77 == 0x50 || bmpId77 == 0x60);

    if (foundImu || foundBmp) {
      activeSda = sda;
      activeScl = scl;
      Serial.printf("[OK] Auto-detected I2C pins: SDA=%d, SCL=%d (BMI160=%d, BMP388=%d)\n",
                    sda, scl, foundImu, foundBmp);
      Serial.print("[I2C SCAN] Devices found: ");
      for (uint8_t addr = 0x08; addr <= 0x77; addr++) {
        Wire.beginTransmission(addr);
        if (Wire.endTransmission() == 0) {
          Serial.printf("0x%02X(id00=0x%02X,id0F=0x%02X) ", addr, i2cReadByte(addr, 0x00), i2cReadByte(addr, 0x0F));
        }
      }
      Serial.println();
      return;
    }
  }

  // Fallback to default pins
  Wire.end();
  Wire.begin();
  Wire.setClock(100000);
  Serial.println("[WARN] Could not auto-detect BMI160/BMP388 on candidate I2C pins.");
}

void setup() {
  Serial.begin(115200);
  delay(500);

  Serial.println("==================================================");
  Serial.println(" ESP32-C6 ZUPT/PDR BLE Sensor (BMI160 + BMP388)   ");
  Serial.println("==================================================");

  autoDetectI2CPins();
  bmi160Ready = initBMI160();
  calibrateGyroBias();
  bmp388Ready = initBMP388();

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

    float pressure = readBMP388PressureHpa();

    // Note: BMI160 is a 6-axis IMU (no magnetometer), so "yaw" is omitted
    // and Android app fuses phone compass yaw with ESP32 gyro/accel.
    char payload[256];
    if (pressure > 300.0f) {
      snprintf(payload, sizeof(payload),
        "{\"accel\":{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f},\"gyro\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},\"barometer\":{\"pressure\":%.2f},\"i2c\":[%d,%d]}",
        ax, ay, az, gx, gy, gz, pressure, activeSda, activeScl
      );
    } else {
      snprintf(payload, sizeof(payload),
        "{\"accel\":{\"x\":%.3f,\"y\":%.3f,\"z\":%.3f},\"gyro\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f},\"i2c\":[%d,%d]}",
        ax, ay, az, gx, gy, gz, activeSda, activeScl
      );
    }

    if (deviceConnected) {
      pTxCharacteristic->setValue((uint8_t*)payload, strlen(payload));
      pTxCharacteristic->notify();
    }
  }
}
