FROM debian:bookworm-slim AS python-builder

ARG PYTHON_VERSION=3.14.6
ARG PYTHON_SHA256=143b1dddefaec3bd2e21e3b839b34a2b7fb9842272883c576420d605e9f30c63

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl xz-utils \
        libbz2-dev libffi-dev libgdbm-dev liblzma-dev libncursesw5-dev \
        libreadline-dev libsqlite3-dev libssl-dev tk-dev uuid-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp/python-build
RUN curl -fsSLO "https://www.python.org/ftp/python/${PYTHON_VERSION}/Python-${PYTHON_VERSION}.tar.xz" \
    && echo "${PYTHON_SHA256}  Python-${PYTHON_VERSION}.tar.xz" | sha256sum -c - \
    && tar -xJf "Python-${PYTHON_VERSION}.tar.xz" --strip-components=1 \
    && ./configure \
        --prefix=/opt/python \
        --disable-gil \
        --enable-shared \
        --with-ensurepip=install \
    && make -j"$(nproc)" \
    && make install \
    && LD_LIBRARY_PATH=/opt/python/lib \
        /opt/python/bin/python3.14t -m pip install --upgrade pip==26.1.2

FROM debian:bookworm-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PATH=/opt/python/bin:${PATH} \
    LD_LIBRARY_PATH=/opt/python/lib

COPY --from=python-builder /opt/python /opt/python

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates libbz2-1.0 libffi8 libgdbm6 liblzma5 libncursesw6 \
        libreadline8 libsqlite3-0 libssl3 libuuid1 zlib1g \
    && rm -rf /var/lib/apt/lists/*

RUN ln -s /opt/python/bin/python3.14t /usr/local/bin/python \
    && ln -s /opt/python/bin/python3.14t /usr/local/bin/python3

WORKDIR /app

COPY requirements.txt ./
RUN DISABLE_SQLALCHEMY_CEXT=1 \
        python -m pip install --no-binary SQLAlchemy SQLAlchemy==2.0.51 \
    && python -m pip install -r requirements.txt \
    && python -m playwright install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONPATH=/app

COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app
COPY scripts ./scripts

RUN chmod +x /app/scripts/docker-entrypoint.sh \
    && python -c "from app.core.free_threading import assert_free_threaded_runtime; assert_free_threaded_runtime()"

EXPOSE 8000

ENTRYPOINT ["/app/scripts/docker-entrypoint.sh"]
CMD ["api"]
