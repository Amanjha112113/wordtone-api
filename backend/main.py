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
    "gemini-1.5-flash",
    "gemini-1.5-flash-8b",
    "gemini-2.0-flash-exp",
    "gemini-1.5-pro"
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
            if key and isinstance(key, str):
                key_len = len(key)
                # Avoid slice shorthand for lint compatibility
                snippet = key if key_len < 6 else key[key_len-6:key_len]
                print(f"Key ...{snippet} marked exhausted for 24h")

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
    # Avoid slice shorthand for lint compatibility
    text_len = len(text)
    snippet = text if text_len < 300 else text[0:300]
    raise ValueError(f"Cannot parse: {snippet}")

async def call_gemini(prompt: str) -> str:
    tried_keys = set()
    while True:
        entry = None
        now = time.time()
        for e in KEYS:
            if e["key"] in tried_keys:
                continue
            if e["exhausted_at"] and (now - e["exhausted_at"]) < 86400:
                continue
            entry = e
            break
            
        if not entry:
            raise HTTPException(status_code=429, detail="All API keys exhausted or failed for this request. Try again later.")

        key = entry["key"]
        tried_keys.add(key)

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

For the 'grammar' tone, provide a version with corrected grammar. If the input is already grammatically correct, provide the original text. For each tone (including grammar), give exactly 3 variations.

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
    try:
        raw = await call_gemini(prompt)
        data = extract_json(raw)
    except Exception as e:
        print(f"Error in rewrite_all: {str(e)}")
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Failed to process AI response: {str(e)}")

    result = {}
    for tone in TONES:
        val = data.get(tone)
        if isinstance(val, list):
            # Take up to 3 elements using a loop for maximum lint safety
            final_list = []
            for item in val:
                if len(final_list) < 3:
                    final_list.append(item)
            result[tone] = final_list
        elif val:
            result[tone] = [str(val)]
        else:
            result[tone] = [req.text]

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
