FROM python:3.11-slim

WORKDIR /app

RUN useradd -m -u 1000 appuser

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY static ./static
COPY server.py .
RUN chown -R appuser:appuser /app

EXPOSE 8080

USER appuser

CMD ["python", "-u", "server.py"]
