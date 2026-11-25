from typing import Literal
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field
from agents.llm_config import get_llm, log_agent_output

llm = get_llm()


class EvaluatorOutput(BaseModel):
    evaluation_result: Literal["COMPLETED", "RETRY"] = Field(
        description="COMPLETED if worker_reports satisfy user_request, otherwise RETRY."
    )
    is_approved: bool = Field(
        description="True if evaluation_result is COMPLETED, else False."
    )


evaluator_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        """
        You are the Evaluator Agent in Neuro.
        Verify whether the worker reports completely and accurately satisfy the user's original request.
        """,
    ),
    (
        "human",
        """
        Original request: {user_request}
        Task plan: {task_plan}
        Worker reports: {worker_reports}

        Return COMPLETED (is_approved=True) or RETRY (is_approved=False).
        """,
    ),
])

evaluator_llm = llm.with_structured_output(EvaluatorOutput)
evaluator_chain = evaluator_prompt | evaluator_llm


async def evaluator(state: dict) -> dict:
    response = await evaluator_chain.ainvoke({
        "user_request": state["user_request"],
        "task_plan": list(state.get("task_plan") or []),
        "worker_reports": state.get("worker_reports", []),
    })

    result = {
        "evaluation_result": response.evaluation_result,
        "is_approved": response.is_approved,
        "iteration": state.get("iteration", 0) + 1,
        "overall_status": "approved" if response.is_approved else "retry_needed",
    }
    log_agent_output("evaluator", result)
    return result
