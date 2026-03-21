from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from google import genai
from google.genai import types
import os
import json
import time
from collections import defaultdict

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])

user_call_count = defaultdict(list)
MAX_CALLS_PER_DAY = 50

class RewriteRequest(BaseModel):
    text: str
    tone: str

@app.post("/rewrite")
async def rewrite(req: RewriteRequest, request: Request):
    user_ip = request.client.host
    now = time.time()
    one_day_ago = now - 86400
    user_call_count[user_ip] = [t for t in user_call_count[user_ip] if t > one_day_ago]

    if len(user_call_count[user_ip]) >= MAX_CALLS_PER_DAY:
        raise HTTPException(status_code=429, detail="Daily limit reached. Try again tomorrow.")

    user_call_count[user_ip].append(now)

    prompt = f"""Rewrite the following message in a {req.tone} tone.
Return ONLY a valid JSON array with exactly 3 string variations.
No explanation, no markdown, no extra text — just the raw JSON array.

Message: "{req.text}"

Expected output format:
["variation 1", "variation 2", "variation 3"]"""

    try:
        response = client.models.generate_content(
            model="gemini-flash-latest",
            contents=prompt,
            config=types.GenerateContentConfig(
                temperature=0.9,
                max_output_tokens=500,
            )
        )

        raw = response.text.strip()

        if raw.startswith("```"):
            raw = raw.split("```")[1]
            if raw.startswith("json"):
                raw = raw[4:]
            raw = raw.strip()

        variations = json.loads(raw)

        if not isinstance(variations, list) or len(variations) < 3:
            raise ValueError("Invalid response format")

        return {"variations": variations[:3]}

    except json.JSONDecodeError:
        raise HTTPException(status_code=500, detail="AI returned invalid format. Retry.")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {"status": "ok", "model": "gemini-flash-latest"}


@app.get("/remaining/{ip}")
async def remaining_calls(ip: str):
    now = time.time()
    one_day_ago = now - 86400
    used = len([t for t in user_call_count.get(ip, []) if t > one_day_ago])
    return {"used": used, "remaining": MAX_CALLS_PER_DAY - used, "limit": MAX_CALLS_PER_DAY}




'''
to update the api key :
railway service
railway variables set GEMINI_API_KEY=hgfkgfk
railway up        


https://railway.com/project/  main account amanjha112113
'''