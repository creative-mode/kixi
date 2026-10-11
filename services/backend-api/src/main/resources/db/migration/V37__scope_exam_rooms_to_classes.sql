ALTER TABLE exam_rooms ADD COLUMN class_id BIGINT;
ALTER TABLE exam_rooms ADD CONSTRAINT fk_exam_rooms_class FOREIGN KEY (class_id) REFERENCES classes(id);
CREATE INDEX idx_exam_rooms_class ON exam_rooms(class_id);
