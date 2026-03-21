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

# Load all available API keys
API_KEYS = []
for k, v in os.environ.items():
    if k.startswith("GEMINI_API_KEY") and v.strip():
        API_KEYS.append(v.strip())

if not API_KEYS:
    API_KEYS.append(None) # Fallback

# Deduplicate to avoid redundant limits hitting
CLIENTS = [genai.Client(api_key=key) for key in list(set(API_KEYS))]

MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-flash-lite-latest",
    "gemini-3.1-flash-lite-preview",
    "gemini-2.5-flash"
]

TONES = ["casual", "professional", "polite", "romantic", "gen-z", "witty", "formal", "friendly"]

class RewriteRequest(BaseModel):
    text: str

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
    raise ValueError(f"Could not parse: {text[:300]}")

async def call_gemini(prompt: str) -> str:
    last_err = None
    for client_instance in CLIENTS:
        for model in MODELS:
            try:
                r = await client_instance.aio.models.generate_content(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        temperature=0.85, 
                        max_output_tokens=2000,
                        response_mime_type="application/json"
                    )
                )
                return r.text.strip()
            except Exception as e:
                last_err = e
                err_str = str(e)
                # Ignore quota limits, disabled models, invalid keys to try next client/model
                if any(x in err_str for x in ["429", "404", "NOT_FOUND", "400", "INVALID"]):
                    continue
                # For safety, also continue on 500s or timeouts
                continue
                
    err_msg = str(last_err) if last_err else "Unknown error"
    raise HTTPException(status_code=429, detail=f"API quotas exhausted across all available keys and models. Last error: {err_msg[:200]}")

@app.post("/rewrite-all")
async def rewrite_all(req: RewriteRequest):
    # One call — get ALL tones at once
    tones_str = ", ".join(TONES)
    prompt = f"""Rewrite the following message in ALL of these tones: {tones_str}.

For each tone, give exactly 3 different variations.

Return ONLY a valid JSON object like this (no markdown, no extra text):
{{
  "casual":       ["variation 1", "variation 2", "variation 3"],
  "professional": ["variation 1", "variation 2", "variation 3"],
  "polite":       ["variation 1", "variation 2", "variation 3"],
  "romantic":     ["variation 1", "variation 2", "variation 3"],
  "gen-z":        ["variation 1", "variation 2", "variation 3"],
  "witty":        ["variation 1", "variation 2", "variation 3"],
  "formal":       ["variation 1", "variation 2", "variation 3"],
  "friendly":     ["variation 1", "variation 2", "variation 3"]
}}

Original message: "{req.text}"
"""
    raw = await call_gemini(prompt)
    data = extract_json(raw)

    # Ensure all tones present
    result = {}
    for tone in TONES:
        val = data.get(tone, [req.text])
        if isinstance(val, list):
            result[tone] = val[:3]
        else:
            result[tone] = [str(val)]

    return {"rewrites": result}


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODELS[0]}