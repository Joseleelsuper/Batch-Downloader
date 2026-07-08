from fastapi import FastAPI

app = FastAPI(title="Batch Downloader Semantic Service")


@app.get("/semantic/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "semantic-service"}
