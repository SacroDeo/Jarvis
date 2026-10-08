interface Env {
  LLM_API_KEY: string;
  JARVIS_BEARER_TOKEN: string;
  LLM_PROVIDER: string;
  LLM_BASE_URL: string;
  LLM_MODEL: string;
  RATE_LIMIT_RPM: string;
  REQUEST_TIMEOUT_MS: string;
  CORS_ORIGIN: string;
}

interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
}

interface ChatRequest {
  messages: ChatMessage[];
  model?: string;
  stream?: boolean;
  temperature?: number;
  max_tokens?: number;
}

interface ChatResponse {
  id: string;
  object: string;
  created: number;
  model: string;
  choices: Array<{
    index: number;
    message: ChatMessage;
    finish_reason: string | null;
  }>;
  usage: {
    prompt_tokens: number;
    completion_tokens: number;
    total_tokens: number;
  };
}

interface ModelsResponse {
  object: string;
  data: Array<{
    id: string;
    object: string;
    created: number;
    owned_by: string;
  }>;
}

interface HealthResponse {
  status: "ok" | "degraded" | "down";
  provider: string;
  model: string;
  timestamp: string;
}

const RATE_LIMIT_WINDOW_MS = 60_000;
const rateLimitStore = new Map<string, number[]>();

async function checkRateLimit(ip: string, limit: number): Promise<boolean> {
  const now = Date.now();
  const windowStart = now - RATE_LIMIT_WINDOW_MS;

  const requests = rateLimitStore.get(ip) || [];
  const validRequests = requests.filter((ts) => ts > windowStart);

  if (validRequests.length >= limit) {
    rateLimitStore.set(ip, validRequests);
    return false;
  }

  validRequests.push(now);
  rateLimitStore.set(ip, validRequests);
  return true;
}

function createCorsHeaders(origin: string, env: Env): Record<string, string> {
  const allowedOrigin = env.CORS_ORIGIN === "*" ? "*" : env.CORS_ORIGIN;
  return {
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "86400",
  };
}

function verifyAuth(request: Request, env: Env): boolean {
  const authHeader = request.headers.get("Authorization");
  if (!authHeader) return false;
  const [scheme, token] = authHeader.split(" ");
  if (scheme !== "Bearer") return false;
  return token === env.JARVIS_BEARER_TOKEN;
}

async function callLLM(
  messages: ChatMessage[],
  model: string,
  env: Env,
  signal: AbortSignal
): Promise<ChatResponse> {
  const url = `${env.LLM_BASE_URL}/chat/completions`;
  const body = JSON.stringify({
    model,
    messages,
    stream: false,
    temperature: 0.7,
    max_tokens: 2048,
  });

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${env.LLM_API_KEY}`,
      "Content-Type": "application/json",
    },
    body,
    signal,
  });

  if (!response.ok) {
    const errorText = await response.text().catch(() => "");
    throw new Error(`LLM API error: ${response.status} ${response.statusText} - ${errorText}`);
  }

  return response.json() as Promise<ChatResponse>;
}

async function fetchModels(env: Env, signal: AbortSignal): Promise<ModelsResponse> {
  const url = `${env.LLM_BASE_URL}/models`;
  const response = await fetch(url, {
    method: "GET",
    headers: {
      "Authorization": `Bearer ${env.LLM_API_KEY}`,
      "Content-Type": "application/json",
    },
    signal,
  });

  if (!response.ok) {
    throw new Error(`Models API error: ${response.status}`);
  }

  return response.json() as Promise<ModelsResponse>;
}

function createErrorResponse(
  status: number,
  message: string,
  env: Env,
  origin: string
): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...createCorsHeaders(origin, env),
    },
  });
}

function createSuccessResponse<T>(
  data: T,
  env: Env,
  origin: string,
  status = 200
): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...createCorsHeaders(origin, env),
    },
  });
}

export default {
  async fetch(request: Request, env: Env, _ctx: ExecutionContext): Promise<Response> {
    const origin = request.headers.get("Origin") || "";
    const url = new URL(request.url);
    const path = url.pathname;

    const corsHeaders = createCorsHeaders(origin, env);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (path === "/health") {
      if (request.method !== "GET") {
        return createErrorResponse(405, "Method not allowed", env, origin);
      }

      const health: HealthResponse = {
        status: "ok",
        provider: env.LLM_PROVIDER,
        model: env.LLM_MODEL,
        timestamp: new Date().toISOString(),
      };

      return createSuccessResponse(health, env, origin);
    }

    if (path === "/models") {
      if (request.method !== "GET") {
        return createErrorResponse(405, "Method not allowed", env, origin);
      }

      if (!verifyAuth(request, env)) {
        return createErrorResponse(401, "Unauthorized", env, origin);
      }

      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), parseInt(env.REQUEST_TIMEOUT_MS));
        const models = await fetchModels(env, controller.signal);
        clearTimeout(timeoutId);
        return createSuccessResponse(models, env, origin);
      } catch (error) {
        if (error instanceof Error && error.name === "AbortError") {
          return createErrorResponse(504, "Models request timeout", env, origin);
        }
        return createErrorResponse(502, "Failed to fetch models", env, origin);
      }
    }

    if (path === "/chat") {
      if (request.method !== "POST") {
        return createErrorResponse(405, "Method not allowed", env, origin);
      }

      if (!verifyAuth(request, env)) {
        return createErrorResponse(401, "Unauthorized", env, origin);
      }

      const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
      const rateLimit = parseInt(env.RATE_LIMIT_RPM);
      const allowed = await checkRateLimit(clientIp, rateLimit);
      if (!allowed) {
        return createErrorResponse(429, "Rate limit exceeded", env, origin);
      }

      let chatRequest: ChatRequest;
      try {
        const text = await request.text();
        if (!text) {
          return createErrorResponse(400, "Empty request body", env, origin);
        }
        chatRequest = JSON.parse(text);
      } catch {
        return createErrorResponse(400, "Invalid JSON", env, origin);
      }

      if (!chatRequest.messages || !Array.isArray(chatRequest.messages) || chatRequest.messages.length === 0) {
        return createErrorResponse(400, "Missing or empty messages array", env, origin);
      }

      for (const msg of chatRequest.messages) {
        if (!msg.role || !msg.content) {
          return createErrorResponse(400, "Each message must have role and content", env, origin);
        }
        if (!["system", "user", "assistant", "tool"].includes(msg.role)) {
          return createErrorResponse(400, `Invalid role: ${msg.role}`, env, origin);
        }
      }

      const model = chatRequest.model || env.LLM_MODEL;

      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), parseInt(env.REQUEST_TIMEOUT_MS));

        const llmResponse = await callLLM(chatRequest.messages, model, env, controller.signal);

        clearTimeout(timeoutId);

        const response: ChatResponse = {
          id: llmResponse.id,
          object: "chat.completion",
          created: llmResponse.created,
          model: llmResponse.model,
          choices: llmResponse.choices.map((choice, index) => ({
            index,
            message: choice.message,
            finish_reason: choice.finish_reason,
          })),
          usage: llmResponse.usage,
        };

        return createSuccessResponse(response, env, origin);
      } catch (error) {
        if (error instanceof Error && error.name === "AbortError") {
          return createErrorResponse(504, "Request timeout", env, origin);
        }
        const message = error instanceof Error ? error.message : "Internal server error";
        return createErrorResponse(502, message, env, origin);
      }
    }

    return createErrorResponse(404, "Not found", env, origin);
  },
} satisfies ExportedHandler<Env>;