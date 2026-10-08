# Jarvis Worker — Cloudflare Workers AI Gateway

A lightweight, stateless AI gateway for the Jarvis Android assistant. Forwards sanitized conversation messages to an OpenAI-compatible LLM API (Groq or OpenRouter) and returns the response. Runs on Cloudflare Workers Free tier.

## Features

- **POST /chat** — Non-streaming chat completion (OpenAI-compatible)
- **GET /health** — Health check endpoint
- **GET /models** — Lists available models from the LLM provider
- **Bearer token authentication** — Protects the Worker from unauthorized access
- **Rate limiting** — Per-IP request limiting (configurable)
- **CORS support** — Configured for Android client
- **No logging of private data** — Request bodies, API keys, and conversation content are never logged
- **Stateless** — No KV/D1 storage (session management handled by Android client)

## Prerequisites

- Node.js 18+
- Cloudflare account
- Wrangler CLI (`npm install -g wrangler` or use `npx wrangler`)
- Groq API key (https://console.groq.com/keys) **OR** OpenRouter API key (https://openrouter.ai/keys)

## Installation

```bash
cd jarvis-worker
npm install
```

## Authentication with Cloudflare

```bash
# Login to Cloudflare (opens browser)
npx wrangler login

# Or use API token
# Set CLOUDFLARE_API_TOKEN environment variable
```

## Configure Secrets

**Never hard-code secrets.** Use Wrangler secrets for production:

```bash
# LLM API Key (Groq or OpenRouter)
npx wrangler secret put LLM_API_KEY

# JARVIS Bearer Token (generate with: openssl rand -hex 32)
npx wrangler secret put JARVIS_BEARER_TOKEN
```

For local development, copy `.dev.vars.example` to `.dev.vars` and fill in values:

```bash
cp .dev.vars.example .dev.vars
# Edit .dev.vars with your keys
```

## Local Development

```bash
# Start local dev server (uses .dev.vars)
npm run dev

# In another terminal, test health endpoint
npm run test:health

# Test chat endpoint (requires .dev.vars with valid keys)
npm run test:chat
```

Expected `/health` response:
```json
{
  "status": "ok",
  "provider": "groq",
  "model": "llama-3.1-70b-versatile",
  "timestamp": "2024-10-05T..."
}
```

Expected `/chat` response:
```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1728123456,
  "model": "llama-3.1-70b-versatile",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "Hello! How can I help you?"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}
}
```

## Deploy to Cloudflare

```bash
# Deploy to Cloudflare Workers
npm run deploy

# Or with wrangler directly
npx wrangler deploy
```

After deployment, your Worker will be available at:
`https://jarvis-worker.<your-subdomain>.workers.dev`

## Testing Endpoints

### Health Check (no auth required)
```bash
curl https://jarvis-worker.<your-subdomain>.workers.dev/health
```

### Models List (requires auth)
```bash
curl -H "Authorization: Bearer YOUR_JARVIS_BEARER_TOKEN" \
     https://jarvis-worker.<your-subdomain>.workers.dev/models
```

### Chat Completion (requires auth)
```bash
curl -X POST https://jarvis-worker.<your-subdomain>.workers.dev/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JARVIS_BEARER_TOKEN" \
  -d '{
    "messages": [
      {"role": "user", "content": "Hello, Jarvis!"}
    ]
  }'
```

### Test Authentication Failure
```bash
# Should return 401 Unauthorized
curl -X POST https://jarvis-worker.<your-subdomain>.workers.dev/chat \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "test"}]}'
```

### Test Malformed JSON
```bash
# Should return 400 Invalid JSON
curl -X POST https://jarvis-worker.<your-subdomain>.workers.dev/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JARVIS_BEARER_TOKEN" \
  -d 'not valid json'
```

### Test Rate Limit
```bash
# Exceed RATE_LIMIT_RPM (default 60/min) to test 429 response
for i in {1..65}; do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "Authorization: Bearer YOUR_JARVIS_BEARER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"messages":[{"role":"user","content":"test"}]}' \
    https://jarvis-worker.<your-subdomain>.workers.dev/chat
done
echo
```

## Switching Between Groq and OpenRouter

### Option 1: Environment Variables (wrangler.toml)

Edit `wrangler.toml`:

```toml
[vars]
LLM_PROVIDER = "openrouter"
LLM_BASE_URL = "https://openrouter.ai/api/v1"
LLM_MODEL = "anthropic/claude-3.5-sonnet"
# or: meta-llama/llama-3.1-70b-instruct, etc.
```

Then redeploy: `npm run deploy`

### Option 2: Runtime Configuration (Android App)

The Android app can send `model` in the request body to override the default:

```json
{
  "messages": [...],
  "model": "anthropic/claude-3.5-sonnet"
}
```

The Worker will use this model if provided, otherwise falls back to `LLM_MODEL` from config.

### Provider-Specific Notes

| Provider | Base URL | Example Models | Auth Header |
|----------|----------|----------------|-------------|
| Groq | `https://api.groq.com/openai/v1` | `llama-3.1-70b-versatile`, `llama-3.1-8b-instant`, `mixtral-8x7b-32768` | `Bearer <key>` |
| OpenRouter | `https://openrouter.ai/api/v1` | `anthropic/claude-3.5-sonnet`, `meta-llama/llama-3.1-70b-instruct`, `openai/gpt-4o-mini` | `Bearer <key>` |

Both use OpenAI-compatible API format.

## Environment Variables / Secrets

### Required Secrets (set via `wrangler secret put`)

| Secret | Description |
|--------|-------------|
| `LLM_API_KEY` | Groq or OpenRouter API key |
| `JARVIS_BEARER_TOKEN` | Bearer token for Android client authentication |

### Configurable Variables (in `wrangler.toml` or `.dev.vars`)

| Variable | Default | Description |
|----------|---------|-------------|
| `LLM_PROVIDER` | `groq` | `groq` or `openrouter` |
| `LLM_BASE_URL` | `https://api.groq.com/openai/v1` | LLM API base URL |
| `LLM_MODEL` | `llama-3.1-70b-versatile` | Default model to use |
| `RATE_LIMIT_RPM` | `60` | Requests per minute per IP |
| `REQUEST_TIMEOUT_MS` | `30000` | Request timeout in milliseconds |
| `CORS_ORIGIN` | `*` | Allowed CORS origin |

## Security: What the Worker MUST NEVER Log

The Worker treats all input as untrusted. The following MUST NEVER be logged:

- ❌ Request bodies (contain conversation messages)
- ❌ `LLM_API_KEY` or any API credentials
- ❌ `JARVIS_BEARER_TOKEN`
- ❌ Phone numbers, email addresses, passwords
- ❌ API keys, tokens, authentication credentials
- ❌ Home addresses, personal contact information
- ❌ Any PII (Personally Identifiable Information)
- ❌ Conversation content (user messages, assistant responses)

The Android client is responsible for sanitizing/tokenizing private information before sending. The Worker simply forwards the sanitized content.

## Architecture

```
Android App (Jarvis)          Cloudflare Worker           LLM Provider (Groq/OpenRouter)
      │                            │                              │
      │  POST /chat (Bearer auth)   │                              │
      │  {messages: [...]}  ──────► │                              │
      │                            │  POST /chat/completions      │
      │                            │  {model, messages, stream}  │
      │                            │  ─────────────────────────► │
      │                            │                              │
      │                            │  {choices, usage, ...}      │
      │                            │  ◄───────────────────────── │
      │                            │                              │
      │  {id, choices, usage} ◄──── │                              │
      │                            │                              │
```

## Free Tier Limits

Cloudflare Workers Free Plan:
- 100,000 requests/day
- 10ms CPU time per request
- 128MB memory
- 30s max execution time

Groq Free Tier:
- 30 requests/minute
- 6,000 tokens/minute
- 14,400 requests/day

OpenRouter Free Tier:
- Varies by model (some free models available)

## Troubleshooting

### "Worker exceeded CPU time limit"
- Reduce `max_tokens` in request
- Use a smaller/faster model (e.g., `llama-3.1-8b-instant` on Groq)

### "Rate limit exceeded" (429)
- Increase `RATE_LIMIT_RPM` in config
- Implement exponential backoff on Android client

### "Request timeout" (504)
- Increase `REQUEST_TIMEOUT_MS`
- Use a faster model

### CORS errors
- Set `CORS_ORIGIN` to your Android app's origin (or `*` for dev)
- Ensure Android app sends `Origin` header

## License

Apache-2.0