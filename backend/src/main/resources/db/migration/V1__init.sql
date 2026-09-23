-- Первая миграция. Применённую миграцию НЕ правим — добавляем новую V{n}__*.sql.
-- Образ pgvector/pgvector:pg16 несёт расширение, но включает его миграция.
CREATE EXTENSION IF NOT EXISTS vector;

-- Дальше — таблицы проекта:
-- CREATE TABLE ... ;
