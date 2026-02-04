FROM python:3.11-slim

WORKDIR /app

# Instalar dependências do sistema para OCR (Tesseract)
RUN apt-get update && apt-get install -y \
  tesseract-ocr \
  tesseract-ocr-por \
  libgl1 \
  libglib2.0-0 \
  && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
