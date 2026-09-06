"""Minimal reference server built exclusively from the official A2A Python SDK."""

import os

import uvicorn
from starlette.applications import Starlette

from a2a.helpers.proto_helpers import new_task_from_user_message, new_text_message
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.types import AgentCapabilities, AgentCard, AgentInterface, AgentSkill


class InteropExecutor(AgentExecutor):
    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        if context.message is None:
            raise ValueError("A message is required")
        task = context.current_task or new_task_from_user_message(context.message)
        if context.current_task is None:
            await event_queue.enqueue_event(task)
        updater = TaskUpdater(event_queue, task.id, task.context_id)
        await updater.complete(
            new_text_message("Hello from the official A2A Python SDK", task.context_id, task.id)
        )

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        if context.task_id is None or context.context_id is None:
            raise ValueError("A task and context are required")
        await TaskUpdater(event_queue, context.task_id, context.context_id).cancel()


def application(host: str) -> Starlette:
    card = AgentCard(
        name="Official A2A Python SDK reference",
        description="Isolated interoperability reference server",
        version="1.0.0",
        supported_interfaces=[
            AgentInterface(
                url=f"http://{host}", protocol_binding="JSONRPC", protocol_version="1.0"
            )
        ],
        capabilities=AgentCapabilities(streaming=False, push_notifications=False),
        default_input_modes=["text"],
        default_output_modes=["text"],
        skills=[
            AgentSkill(
                id="interop",
                name="Interoperability",
                description="Completes an interoperability task",
                tags=["interop"],
            )
        ],
    )
    handler = DefaultRequestHandler(
        agent_executor=InteropExecutor(), task_store=InMemoryTaskStore(), agent_card=card
    )
    return Starlette(
        routes=[
            *create_agent_card_routes(agent_card=card),
            *create_jsonrpc_routes(request_handler=handler, rpc_url="/"),
        ]
    )


if __name__ == "__main__":
    public_host = os.environ.get("REFERENCE_HOST", "a2a-reference:9999")
    uvicorn.run(application(public_host), host="0.0.0.0", port=9999, log_level="info")
