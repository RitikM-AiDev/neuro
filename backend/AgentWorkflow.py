from langgraph.graph import StateGraph, START, END
from validations import NeuroState
from agents.orchestrator import orchestrator
from agents.worker1 import worker1
from agents.worker2 import worker2
from agents.planner import planner
from agents.evaluator import evaluator

graph = StateGraph(NeuroState)


def route_after_orchestrator(state: NeuroState) -> str:
    assigned = state.get("assigned_agent")
    if assigned in ("worker1", "worker2", "planner", "evaluator"):
        return assigned
    return "end"


graph.add_node("orchestrator", orchestrator)
graph.add_node("planner", planner)
graph.add_node("worker1", worker1)
graph.add_node("worker2", worker2)
graph.add_node("evaluator", evaluator)

graph.add_edge(START, "planner")
graph.add_edge("planner", "orchestrator")
graph.add_conditional_edges(
    "orchestrator",
    route_after_orchestrator,
    {
        "worker1": "worker1",
        "planner": "planner",
        "worker2": "worker2",
        "evaluator": "evaluator",
        "end": END,
    },
)
graph.add_edge("worker1", "orchestrator")
graph.add_edge("worker2", "orchestrator")
graph.add_edge("evaluator", "orchestrator")


neuro_workflow = graph.compile()
