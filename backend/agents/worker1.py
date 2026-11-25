from langchain_core.prompts import ChatPromptTemplate
from agents.llm_config import get_llm, log_agent_output, resolve_task

llm = get_llm()

worker1_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        """
        You are Worker 1 (Research & Reasoning Agent) in Neuro.
        Execute your assigned task accurately, concisely, and thoroughly.
        """,
    ),
    (
        "human",
        """
        User request: {user_request}
        Assigned task: {task}
        Previous worker reports: {worker_reports}

        Provide the execution result for your assigned task.
        """,
    ),
])

worker1_chain = worker1_prompt | llm


async def worker1(state: dict) -> dict:
    task = resolve_task(state)
    response = await worker1_chain.ainvoke({
        "user_request": state["user_request"],
        "task": task,
        "worker_reports": state.get("worker_reports", []),
    })

    result = {
        "current_task": task,
        "task_status": True,
        "worker_reports": [
            {
                "worker": "worker1",
                "task": task,
                "report": response.content,
            }
        ],
    }
    log_agent_output(
        "worker1",
        {
            "task": task,
            "report": response.content,
        },
    )
    return result
