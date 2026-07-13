# Download Worker

Consumes version 1 download jobs from RabbitMQ, downloads HTTPS artifacts with bounded
resources, stores the files, manifest and ZIP in MinIO, and emits versioned result events.

## Input event

Exchange and routing key default to `batch-downloader.events` and
`download.job-created.v1`.

```json
{
  "eventId": "9bbd2bf6-c7ce-4430-802f-45bd77f73c60",
  "eventType": "download.job-created",
  "eventVersion": 1,
  "occurredAt": "2026-07-11T12:00:00Z",
  "jobId": "f0363a9c-d860-45a5-9e61-e57343051ef4",
  "recipientEmail": "persona@example.com",
  "items": [
    {
      "itemId": "firefox-windows-x64",
      "appId": "Mozilla.Firefox",
      "name": "Mozilla Firefox",
      "url": "https://downloads.example.org/Firefox.exe",
      "filename": "Firefox.exe"
    }
  ]
}
```

The consumer accepts only `eventType=download.job-created`, `eventVersion=1`, HTTPS
URLs whose complete DNS answer set is public, unique item IDs and the configured item
count. A valid `recipientEmail` is required. The inbox makes a successfully handled
`eventId` idempotent.

## Output events

- `download.file-downloaded.v1` for every stored file.
- `download.zip-generated.v1` for a completed or partial bundle.
- `download.job-failed.v1` when the request is invalid or no artifact can be downloaded.

Output event IDs and MinIO object keys are deterministic per job, so a Rabbit redelivery
overwrites the same objects and downstream consumers can deduplicate safely.

## Verification

```bash
docker run --rm -v batch-downloader-m2:/root/.m2 \
  -v "$PWD:/workspace" -w /workspace \
  maven:3.9.16-eclipse-temurin-26 mvn -B test
```
