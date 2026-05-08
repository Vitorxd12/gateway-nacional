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

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
from selenium.common.exceptions import TimeoutException

from sisab_scraper import scrape_sisab


# Two-digit IBGE UF prefix → official UF acronym. The UF code (cód. IBGE
# de UF) is canonical and stable; the SISAB form expects the acronym in
# its UF dropdown, so we project the prefix here. Letting the Java client
# pass only `ibge` keeps the public sidecar contract minimal — the gateway
# already keeps its own `IbgeUfLookup` table for symmetry on the JVM side.
_IBGE_UF_PREFIX: dict[str, str] = {
    "11": "RO", "12": "AC", "13": "AM", "14": "RR", "15": "PA", "16": "AP", "17": "TO",
    "21": "MA", "22": "PI", "23": "CE", "24": "RN", "25": "PB", "26": "PE", "27": "AL",
    "28": "SE", "29": "BA",
    "31": "MG", "32": "ES", "33": "RJ", "35": "SP",
    "41": "PR", "42": "SC", "43": "RS",
    "50": "MS", "51": "MT", "52": "GO", "53": "DF",
}


def _uf_from_ibge(ibge: str) -> str | None:
    """Extracts the two-letter UF acronym from an IBGE municipality code."""
    digits = "".join(ch for ch in (ibge or "") if ch.isdigit())
    if len(digits) < 2:
        return None
    return _IBGE_UF_PREFIX.get(digits[:2])

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


class ScrapeResponse(BaseModel):
    """Wire-shape consumed by the Java {@code SisabWebClient.SidecarResponse}."""

    rows: list[dict]
    competencia: str
    empty: bool
    count: int


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.get("/scrape", response_model=ScrapeResponse)
def scrape(
    ibge: str = Query(min_length=6, max_length=7, description="IBGE municipality code (6 or 7 digits)."),
    competencia: str = Query(min_length=7, max_length=7, description='Competency as "yyyy-MM" or "MM/AAAA".'),
) -> ScrapeResponse:
    """Run the headless scrape.

    The endpoint exposes a GET with query parameters so the gateway can
    invoke it as a standard upstream — the body of an HTTP/1.1 request is
    ambiguous to cache layers and CDNs, while query params survive any
    HTTP intermediary cleanly. The UF is derived from the IBGE prefix
    so the public contract stays tight (two params instead of three).

    Latency: cold runs are 10–90 s (SISAB is slow under headless Chromium
    plus the cascade of jQuery.active waits). The Java caller has its own
    Resilience4j ``timelimiter.sisabScraperCB`` configured to 120 s — keep
    that in sync if you tune anything here.
    """
    uf = _uf_from_ibge(ibge)
    if uf is None:
        raise HTTPException(
            status_code=400,
            detail=f"IBGE inválido para derivação de UF: {ibge!r} (precisa de prefixo conhecido).",
        )

    logger.info("Scrape request: uf=%s ibge=%s competencia=%s", uf, ibge, competencia)
    try:
        result = scrape_sisab(uf=uf, ibge=ibge, competencia=competencia)
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
        ibge, result.competencia, len(result.rows), result.empty,
    )
    return ScrapeResponse(
        rows=result.rows,
        competencia=result.competencia,
        empty=result.empty,
        count=len(result.rows),
    )
