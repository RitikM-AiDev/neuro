from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field
from agents.llm_config import get_llm, log_agent_output, resolve_task

llm = get_llm()


class Worker2Output(BaseModel):
    report: str = Field(description="Execution result for the assigned task.")
    tool_name: str = Field(
        default="",
        description="Name of any external/device tool required (e.g. open_app, set_alarm), or empty string if none.",
    )


worker2_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        """
        You are Worker 2 (Action & Tool Execution Agent) in Neuro.
        Execute the assigned task, format any final output clearly, and identify a tool_name if an external/device action is needed.
        """,
    ),
    (
        "human",
        """
        User request: {user_request}
        Assigned task: {task}
        Previous worker reports: {worker_reports}

        Provide the execution result and optional tool_name.
        """,
    ),
])

worker2_llm = llm.with_structured_output(Worker2Output)
worker2_chain = worker2_prompt | worker2_llm


async def worker2(state: dict) -> dict:
    task = resolve_task(state)
    response = await worker2_chain.ainvoke({
        "user_request": state["user_request"],
        "task": task,
        "worker_reports": state.get("worker_reports", []),
    })

    result = {
        "current_task": task,
        "task_status": True,
        "tool_name": response.tool_name,
        "worker_reports": [
            {
                "worker": "worker2",
                "task": task,
                "report": response.report,
            }
        ],
    }
    log_agent_output(
        "worker2",
        {
            "task": task,
            "tool_name": response.tool_name,
            "report": response.report,
        },
    )
    return result
