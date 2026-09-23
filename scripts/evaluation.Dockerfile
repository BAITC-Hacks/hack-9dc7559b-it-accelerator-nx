FROM python:3.12-slim
WORKDIR /workspace
COPY scripts/d1-smoke.py scripts/evaluate.py ./scripts/
COPY data ./data
ENTRYPOINT ["python", "scripts/evaluate.py"]
