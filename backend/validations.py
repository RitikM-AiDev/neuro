from pydantic import BaseModel, Field
from typing import TypedDict, Annotated
from collections import deque
import operator


class UserRequest(BaseModel):
    query: str = Field(
        ...,
        min_length=1,
        max_length=10000,
        description="User's input query"
    )


class NeuroState(TypedDict):
    user_request: str
    task_plan:deque
    current_task_index: int
    assigned_agent : str
    current_task: str
    task_status: bool
    worker_reports: Annotated[list[dict], operator.add]
    evaluation_result: str
    is_approved: bool
    iteration: int
    max_iterations: int
    overall_status: str
    tool_name: str
    final_response: str
