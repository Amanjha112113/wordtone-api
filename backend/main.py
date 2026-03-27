from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from google import genai
from google.genai import types
import os, json, re, time, traceback
from collections import defaultdict
from dotenv import load_dotenv

load_dotenv()

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# Load all keys dynamically and deduplicate
_raw_keys = set()
for k, v in os.environ.items():
    if k.startswith("GEMINI_API_KEY") and v.strip():
        _raw_keys.add(v.strip())

import typing
KEYS: typing.List[typing.Dict[str, typing.Any]] = []
if _raw_keys:
    for k in _raw_keys:
        KEYS.append({"key": k, "exhausted_at": None})
else:
    KEYS.append({"key": None, "exhausted_at": None})

print(f"Loaded {len(KEYS)} API key(s)")

# Use the explicitly verified models that work without quotas
MODELS = [
    "gemini-3.1-flash-lite-preview",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-flash-lite-latest"
]
TONES  = ["casual", "professional", "polite", "grammar", "gen-z", "formal", "friendly"]

class RewriteRequest(BaseModel):
    text: str

def get_active_client():
    now = time.time()
    for entry in KEYS:
        if entry["exhausted_at"]:
            if (now - entry["exhausted_at"]) >= 86400:
                entry["exhausted_at"] = None
            else:
                continue
        return entry
    return None

def mark_exhausted(key: str):
    for e in KEYS:
        if e["key"] == key:
            e["exhausted_at"] = time.time()
            if key:
                print(f"Key ...{key[-6:]} marked exhausted for 24h")

def extract_json(text: str) -> dict:
    try:
        return json.loads(text.strip())
    except: pass
    cleaned = re.sub(r'```(?:json)?', '', text).strip()
    try:
        return json.loads(cleaned)
    except: pass
    match = re.search(r'\{.*\}', text, re.DOTALL)
    if match:
        try:
            return json.loads(match.group())
        except: pass
    raise ValueError(f"Cannot parse: {text[:300]}")

async def call_gemini(prompt: str) -> str:
    tried = set()
    while True:
        entry = get_active_client()
        if not entry:
            raise HTTPException(status_code=429, detail="All API quotas exhausted for the next 24 hours. Try again later.")

        key = entry["key"]
        if key in tried:
            raise HTTPException(status_code=429, detail="All API quotas exhausted for the next 24 hours. Try again later.")
        tried.add(key)

        client = genai.Client(api_key=key)

        for model in MODELS:
            try:
                print(f"Trying ...{key[-6:] if key else 'None'} / {model}")
                r = await client.aio.models.generate_content(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        temperature=0.9,
                        max_output_tokens=3000,
                        response_mime_type="application/json"
                    )
                )
                print(f"Success: ...{key[-6:] if key else 'None'} / {model}")
                return r.text.strip()
            except Exception as e:
                err = str(e)
                # If the key is exhausted/invalid, mark it and break the inner loop to try the next API Key
                if "429" in err or "RESOURCE_EXHAUSTED" in err or "400" in err or "INVALID" in err:
                    mark_exhausted(key)
                    break
                # For 404 or other errors, continue to the next model in the list for THIS key
                continue

@app.post("/rewrite-all")
async def rewrite_all(req: RewriteRequest):
    tones_str = ", ".join(TONES)
    prompt = f"""Rewrite the following message in ALL of these tones: {tones_str}.

For the 'grammar' tone, if the input is grammatically incorrect, provide exactly 3 variations of the corrected text. If the input is already correct, provide the original text as the variations.

For each tone, give exactly 3 different variations.

Return ONLY valid JSON (no markdown, no extra text):
{{
  "casual":       ["variation 1", "variation 2", "variation 3"],
  "professional": ["variation 1", "variation 2", "variation 3"],
  "polite":       ["variation 1", "variation 2", "variation 3"],
  "grammar":      ["variation 1", "variation 2", "variation 3"],
  "gen-z":        ["variation 1", "variation 2", "variation 3"],
  "formal":       ["variation 1", "variation 2", "variation 3"],
  "friendly":     ["variation 1", "variation 2", "variation 3"]
}}

Original message: "{req.text}"
"""
    raw = await call_gemini(prompt)
    data = extract_json(raw)

    result = {}
    for tone in TONES:
        val = data.get(tone, [req.text])
        result[tone] = (list(val)[:3] if isinstance(val, list) else [str(val)])

    return {"rewrites": result}

@app.get("/health")
async def health():
    active = get_active_client()
    statuses = []
    now = time.time()
    for i, e in enumerate(KEYS):
        if e["exhausted_at"]:
            hrs = 24 - (now - e["exhausted_at"]) / 3600
            statuses.append(f"Key {i+1}: exhausted ({hrs:.1f}h left)")
        else:
            statuses.append(f"Key {i+1}: active")
    return {
        "status": "ok" if active else "degraded",
        "keys": statuses,
        "models": MODELS
    }
