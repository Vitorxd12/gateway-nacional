"""
FastAPI wrapper exposing :func:`sisab_scraper.scrape_sisab` over HTTP.

Contract
--------
``POST /scrape``  body: ``{"uf": "BA", "ibge": "291710", "competencia": "2026-02"}``
``GET  /health``  → ``{"status": "ok"}`` for docker healthcheck

Why FastAPI and not Flask
-------------------------
FastAPI gives us request validation via Pydantic and structured error
responses out of the box, with the same async-friendly stack the gateway
uses on the Java side. Synchronous Selenium runs in a worker thread
(FastAPI handles that automatically when the route is ``def`` rather than
``async def``).
"""

from __future__ import annotations

import logging
import os

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from selenium.common.exceptions import TimeoutException

from sisab_scraper import scrape_sisab

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("sisab-sidecar")

app = FastAPI(
    title="CerneBR Gateway — SISAB Sidecar",
    version="1.0.0",
    description=(
        "Headless-Chromium scraper for the SISAB validation report. "
        "Designed to be called as an upstream from the Java gateway, "
        "not exposed to the public internet."
    ),
)


class ScrapeRequest(BaseModel):
    """Request payload — mirrors the Java DTO on the gateway side."""

    uf: str = Field(min_length=2, max_length=2, description="Two-letter state code (e.g. BA).")
    ibge: str = Field(min_length=6, max_length=7, description="Six- or seven-digit IBGE code.")
    competencia: str = Field(
        min_length=7,
        max_length=7,
        description='Competency as "yyyy-MM" (preferred) or "MM/AAAA".',
    )


class ScrapeRow(BaseModel):
    """One row of the SISAB validation table — keys mirror the upstream HTML."""

    REGIAO: str | None = None
    UF: str | None = None
    IBGE: str | None = None
    MUNICIPIO: str | None = None
    CNES: str | None = None
    INE: str | None = None
    VALIDACAO: str | None = None
    TOTAL: int | None = None

    # Permite tail columns que SISAB às vezes adiciona (envio prazo, etc.) sem
    # quebrar a serialização — Pydantic v2 silencia extras com este config.
    model_config = {"extra": "allow"}


class ScrapeResponse(BaseModel):
    rows: list[dict]
    competencia: str
    empty: bool
    count: int


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/scrape", response_model=ScrapeResponse)
def scrape(req: ScrapeRequest) -> ScrapeResponse:
    """Run the headless scrape.

    Latency: cold runs are 30–90 s (SISAB is slow under headless Chromium
    plus the cascade of jQuery.active waits). The Java caller has its own
    Resilience4j ``timelimiter.sisabSidecarCB`` configured to 120 s — keep
    that in sync if you tune anything here.
    """
    logger.info("Scrape request: uf=%s ibge=%s competencia=%s", req.uf, req.ibge, req.competencia)
    try:
        result = scrape_sisab(uf=req.uf, ibge=req.ibge, competencia=req.competencia)
    except ValueError as exc:
        # Bad input — do not punish the caller's circuit breaker.
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except TimeoutException as exc:
        # Most likely upstream is slow or layout drifted. Map to 503 so the
        # gateway's circuit breaker counts it the same way as a SISAB outage.
        logger.warning("SISAB scrape timeout: %s", exc)
        raise HTTPException(status_code=503, detail=f"SISAB scrape timeout: {exc}") from exc
    except Exception as exc:  # noqa: BLE001
        logger.exception("SISAB scrape failed unexpectedly")
        raise HTTPException(status_code=502, detail=f"SISAB scrape error: {type(exc).__name__}: {exc}") from exc

    logger.info(
        "Scrape OK: ibge=%s comp=%s rows=%d empty=%s",
        req.ibge, result.competencia, len(result.rows), result.empty,
    )
    return ScrapeResponse(
        rows=result.rows,
        competencia=result.competencia,
        empty=result.empty,
        count=len(result.rows),
    )
