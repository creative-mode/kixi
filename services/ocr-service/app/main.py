# Entry point for OCR service
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import router

app = FastAPI(
    title="Kixi OCR Service",
    description="Serviço de OCR para extração de texto de imagens",
    version="1.0.0"
)

# CORS configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routes
app.include_router(router, prefix="/api/v1")


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy", "service": "kixi-ocr"}


@app.get("/")
async def root():
    """Root endpoint"""
    return {"message": "Kixi OCR Service", "version": "1.0.0"}
