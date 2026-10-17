# syntax=docker/dockerfile:1.7

# ---- Build: install Volatility 3 and deps into an isolated virtualenv ----
FROM python:3.12-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH
COPY requirements.txt .
RUN pip install -r requirements.txt \
 && vol --help > /dev/null

# Optionally bake the Volatility Windows symbol pack (~800 MB) so analysis works fully offline.
# Without it, Volatility downloads the matching PDB from Microsoft's symbol server per image.
ARG VOL_SYMBOLS=none
RUN mkdir -p /symbols && if [ "$VOL_SYMBOLS" = "windows" ]; then \
      python -c "import urllib.request; urllib.request.urlretrieve('https://downloads.volatilityfoundation.org/volatility3/symbols/windows.zip', '/symbols/windows.zip')"; \
    fi

# ---- Runtime: slim, non-root, no build tooling ----
FROM python:3.12-slim
RUN groupadd -g 10001 worker && useradd -u 10001 -g worker -M -s /usr/sbin/nologin worker \
 && mkdir -p /cache && chown worker:worker /cache \
 # Drop setuid/setgid bits so nothing in the image can escalate privileges.
 && find / -xdev -perm /6000 -type f -exec chmod a-s {} +
COPY --from=build /opt/venv /opt/venv
COPY --from=build /symbols /symbols
WORKDIR /app
COPY worker ./worker
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    WORK_DIR=/tmp/work \
    VOL_CACHE_DIR=/cache \
    HOME=/cache \
    VOL_SYMBOL_DIRS=/symbols \
    PORT=8080
USER 10001
EXPOSE 8080
# One analysis per instance (Cloud Run --concurrency=1); no gunicorn timeout because a push request
# legitimately runs for minutes. Cloud Run's request timeout is the outer bound.
CMD exec gunicorn --bind ":${PORT}" --workers 1 --threads 1 --timeout 0 --graceful-timeout 30 \
    --access-logfile - "worker.app:create_app()"
