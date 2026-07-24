FROM python:3.14.6-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/huggingface \
    SENTENCE_TRANSFORMERS_HOME=/models/sentence-transformers

WORKDIR /app

ARG PYTORCH_CPU_INDEX_URL=https://download.pytorch.org/whl/cpu

COPY pyproject.toml ./
COPY app/__init__.py ./app/__init__.py
RUN python -m pip install --upgrade pip==26.1.2 \
    && python -m pip install \
        --index-url "${PYTORCH_CPU_INDEX_URL}" \
        --only-binary=:all: \
        torch==2.13.0 \
    && python -m pip install .

COPY app ./app
COPY migrations ./migrations

RUN mkdir -p /models /reports

EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
