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
 && mkdir -p /work /cache && chown worker:worker /work /cache \
 # Drop setuid/setgid bits so nothing in the image can escalate privileges.
 && find / -xdev -perm /6000 -type f -exec chmod a-s {} +
COPY --from=build /opt/venv /opt/venv
COPY --from=build /symbols /symbols
WORKDIR /app
COPY worker ./worker
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    WORK_DIR=/work \
    VOL_CACHE_DIR=/cache \
    VOL_SYMBOL_DIRS=/symbols \
    HEARTBEAT_FILE=/tmp/worker-heartbeat
USER 10001
# Healthy while the main loop keeps touching its heartbeat (it does so at least every ~5s when idle).
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD python -c "import os,sys,time; sys.exit(time.time()-os.path.getmtime('/tmp/worker-heartbeat') > 60)"
ENTRYPOINT ["python", "-m", "worker.main"]
