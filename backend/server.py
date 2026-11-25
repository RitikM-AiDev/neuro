from collections import deque
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from validations import UserRequest
from AgentWorkflow import neuro_workflow
from agents.llm_config import log_agent_output

app = FastAPI(
    title="AI Backend",
    description="AI and Agent Backend API",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def home():
    return {
        "status": "success",
        "message": "AI Backend is running!",
    }


@app.get("/health")
async def health():
    return {"status": "healthy"}


@app.post("/api/v1/ai/query")
async def user_request(data: UserRequest):
    print("""------------------------------------------------------------------------\n------------------------------------------------------------\n---------------------------------------------------------------\n------------------------------------------------------------""")
    initial_state = {
        "user_request": data.query,
        "task_plan": deque(),
        "current_task_index": 0,
        "assigned_agent": "",
        "current_task": "",
        "task_status": False,
        "worker_reports": [],
        "evaluation_result": "",
        "is_approved": False,
        "iteration": 0,
        "max_iterations": 3,
        "overall_status": "started",
        "tool_name": "",
        "final_response": "",
    }

    result = await neuro_workflow.ainvoke(initial_state)

    response_payload = {
        "reply": result.get("final_response", ""),
        "access_tool": result.get("tool_name", ""),
        "status": result.get("overall_status", ""),
    }
    log_agent_output("final_api_response", response_payload)
    return response_payload
