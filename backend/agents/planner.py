from collections import deque
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field
from agents.llm_config import get_llm, log_agent_output

llm = get_llm()


class Task(BaseModel):
    task_id: str
    description: str
    status: str = "pending"
    dependencies: list[str] = Field(default_factory=list)


class PlannerOutput(BaseModel):
    tasks: list[Task]


planner_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        """
        You are the Planner Agent in Neuro.

        YOUR RESPONSIBILITY:
        Convert the user's request into executable tasks.

        RULES:
        1. Understand the user's objective.
        2. Break complex requests into manageable tasks.
        3. Create subtasks when necessary.
        4. Identify dependencies between tasks.
        5. Keep tasks specific and executable.
        6. Avoid duplicate tasks.
        7. Set every initial task status to pending.
        8. Do not execute tasks.
        9. Do not assign agents.
        10. Do not evaluate results.

        Return a structured task plan.
        """,
    ),
    (
        "human",
        """
        User request:
        {user_request}

        Create the task execution plan.
        """,
    ),
])

planner_llm = llm.with_structured_output(PlannerOutput)
planner_chain = planner_prompt | planner_llm


async def planner(state: dict) -> dict:
    response = await planner_chain.ainvoke({
        "user_request": state["user_request"],
    })

    result = {
        "task_plan": deque(task.model_dump() for task in response.tasks),
        "current_task_index": 0,
        "overall_status": "planning_completed",
    }
    log_agent_output(
        "planner",
        {
            "user_request": state["user_request"],
            "tasks": list(result["task_plan"]),
            "overall_status": result["overall_status"],
        },
    )
    return result
