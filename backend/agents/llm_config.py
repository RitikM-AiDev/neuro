import os
from dotenv import load_dotenv
from langchain_google_genai import ChatGoogleGenerativeAI

load_dotenv()


def get_llm(model: str = "gemini-3.5-flash", temperature: float = 0) -> ChatGoogleGenerativeAI:
    api_key = (
        os.getenv("GOOGLE_API_KEY")
        or os.getenv("gemini_api_key")
        or os.getenv("GEMINI_API_KEY")
    )
    return ChatGoogleGenerativeAI(
        model=model,
        temperature=temperature,
        google_api_key=api_key,
    )


def resolve_task(state: dict) -> str:
    """Resolve the active task description from state or task_plan."""
    if state.get("current_task"):
        return state["current_task"]
    plan = list(state.get("task_plan") or [])
    idx = state.get("current_task_index", 0)
    if 0 <= idx < len(plan):
        item = plan[idx]
        return item.get("description", str(item)) if isinstance(item, dict) else str(item)
    return state["user_request"]


def log_agent_output(agent_name: str, payload: dict) -> None:
    """Print an agent's output with clear decorative borders for terminal debugging."""
    border = "=" * 68
    sub_border = "-" * 68
    print(f"\n{border}", flush=True)
    print(f"  [AGENT: {agent_name.upper()}]", flush=True)
    print(sub_border, flush=True)
    for key, value in payload.items():
        print(f"  {key}: {value}", flush=True)
    print(f"{border}\n", flush=True)
