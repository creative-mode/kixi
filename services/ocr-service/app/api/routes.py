# FastAPI endpoints for OCR service
from fastapi import APIRouter, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
from PIL import Image
import io
import pytesseract

from app.ocr.engine import OCREngine
from app.ocr.preprocessing import preprocess_image

router = APIRouter(tags=["OCR"])
ocr_engine = OCREngine()


@router.post("/ocr")
async def extract_text(file: UploadFile = File(...)):
    """
    Extract text from an uploaded image using OCR.
    
    Supported formats: PNG, JPG, JPEG, TIFF, BMP
    """
    # Validate file type
    allowed_types = ["image/png", "image/jpeg", "image/jpg", "image/tiff", "image/bmp"]
    if file.content_type not in allowed_types:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid file type. Allowed types: {', '.join(allowed_types)}"
        )
    
    try:
        # Read image
        contents = await file.read()
        image = Image.open(io.BytesIO(contents))
        
        # Preprocess and extract text
        processed_image = preprocess_image(image)
        result = ocr_engine.extract_text(processed_image)
        
        return JSONResponse(content={
            "success": True,
            "filename": file.filename,
            "text": result["text"],
            "confidence": result.get("confidence", None)
        })
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"OCR processing failed: {str(e)}")


@router.post("/ocr/batch")
async def extract_text_batch(files: list[UploadFile] = File(...)):
    """
    Extract text from multiple images.
    """
    results = []
    
    for file in files:
        try:
            contents = await file.read()
            image = Image.open(io.BytesIO(contents))
            processed_image = preprocess_image(image)
            result = ocr_engine.extract_text(processed_image)
            
            results.append({
                "filename": file.filename,
                "success": True,
                "text": result["text"],
                "confidence": result.get("confidence", None)
            })
        except Exception as e:
            results.append({
                "filename": file.filename,
                "success": False,
                "error": str(e)
            })
    
    return JSONResponse(content={"results": results})
