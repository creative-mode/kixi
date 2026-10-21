"""Downloads the PaddleOCR models into the image at build time, so a container starts (and is recreated) with them
already there and never needs the internet to read an exam. Run by the Dockerfile as the runtime user."""

from app.config import settings


def main() -> None:
    from paddleocr import FormulaRecognition, LayoutDetection, PaddleOCR

    detectors = [settings.ocr_det_model_name]
    if settings.ocr_fallback_det_model_name:
        detectors.append(settings.ocr_fallback_det_model_name)  # the thorough one, for the second read
    for detector in detectors:
        PaddleOCR(
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=settings.ocr_use_angle_cls,
            device="cpu",
            text_detection_model_name=detector,
            text_recognition_model_name=settings.ocr_rec_model_name,
            enable_mkldnn=settings.ocr_enable_mkldnn,
        )
    if settings.ocr_layout_enabled:
        LayoutDetection(model_name=settings.ocr_layout_model_name, device="cpu", enable_mkldnn=settings.ocr_enable_mkldnn)
    if settings.ocr_formula_latex and settings.ocr_formula_model_enabled:
        FormulaRecognition(model_name=settings.ocr_formula_model_name, device="cpu")


if __name__ == "__main__":
    main()
