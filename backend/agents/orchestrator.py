from typing import Literal
from langchain_core.prompts import ChatPromptTemplate
from pydantic import BaseModel, Field
from agents.llm_config import get_llm, log_agent_output

llm = get_llm()


class OrchestratorDecision(BaseModel):
    assigned_agent: Literal["worker1", "worker2", "planner", "evaluator", "end"] = Field(
        description="Next node to route to, or 'end' when finished."
    )
    current_task: str = Field(
        default="",
        description="Task description for the chosen worker, if routing to a worker.",
    )
    current_task_index: int = Field(
        default=0,
        description="Index of the task currently being handled.",
    )
    overall_status: str = Field(
        default="in_progress",
        description="Current workflow status.",
    )
    final_response: str = Field(
        default="",
        description="Final user-facing response when assigned_agent is 'end'.",
    )


orchestrator_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        """
        You are the Orchestrator Agent in Neuro.

        Available nodes:
        - "worker1": Research, analysis, reasoning, and general information tasks.
        - "worker2": Action, tool execution, device/app commands, and formatting tasks.
        - "evaluator": Evaluates worker_reports against user_request and task_plan.
        - "planner": Re-plans if task_plan is empty or insufficient.
        - "end": Finish and provide final_response once evaluator approves (is_approved=True) or max_iterations is reached.

        Routing rules:
        1. If is_approved is True, route to "end" and synthesize a clear, concise final_response from worker_reports.
        2. If worker_reports is empty, pick the first pending task from task_plan and route to "worker1" or "worker2".
        3. If there are unexecuted tasks in task_plan and evaluation hasn't been run yet for all tasks, route to the next appropriate worker ("worker1" or "worker2") and advance current_task_index.
        4. Once tasks have worker_reports and is_approved is False, route to "evaluator".
        5. If evaluator returned RETRY (evaluation_result == "RETRY"), route to a worker to fix the issue.
        """,
    ),
    (
        "human",
        """
        User request: {user_request}
        Task plan: {task_plan}
        Current task index: {current_task_index}
        Worker reports: {worker_reports}
        Evaluation result: {evaluation_result}
        Is approved: {is_approved}
        Iteration: {iteration} / {max_iterations}

        Decide the next step.
        """,
    ),
])

orchestrator_llm = llm.with_structured_output(OrchestratorDecision)
orchestrator_chain = orchestrator_prompt | orchestrator_llm


async def orchestrator(state: dict) -> dict:
    iteration = state.get("iteration", 0)
    max_iterations = state.get("max_iterations", 3)

    if iteration >= max_iterations and not state.get("is_approved", False):
        reports = state.get("worker_reports", [])
        fallback_reply = reports[-1].get("report", "") if reports else "Unable to complete the request within the iteration limit."
        result = {
            "assigned_agent": "end",
            "overall_status": "max_iterations_reached",
            "final_response": fallback_reply,
        }
        log_agent_output("orchestrator", result)
        return result

    task_plan = list(state.get("task_plan") or [])
    decision = await orchestrator_chain.ainvoke({
        "user_request": state["user_request"],
        "task_plan": task_plan,
        "current_task_index": state.get("current_task_index", 0),
        "worker_reports": state.get("worker_reports", []),
        "evaluation_result": state.get("evaluation_result", ""),
        "is_approved": state.get("is_approved", False),
        "iteration": iteration,
        "max_iterations": max_iterations,
    })

    result = {
        "assigned_agent": decision.assigned_agent,
        "current_task": decision.current_task,
        "current_task_index": decision.current_task_index,
        "overall_status": decision.overall_status,
        "final_response": decision.final_response,
    }
    log_agent_output("orchestrator", result)
    return result
