"""Independent interoperability probe executed with the official A2A Python SDK."""

import asyncio
import sys

import httpx

from a2a.client import A2ACardResolver, ClientConfig, ClientFactory
from a2a.types import (
    Message,
    Part,
    Role,
    SendMessageConfiguration,
    SendMessageRequest,
    TaskState,
)
from a2a.utils.constants import TransportProtocol


async def run(base_url: str) -> None:
    async with httpx.AsyncClient(timeout=20) as http:
        card = await A2ACardResolver(http, base_url).get_agent_card()
        client = ClientFactory(
            ClientConfig(
                httpx_client=http,
                streaming=False,
                polling=True,
                supported_protocol_bindings=[TransportProtocol.JSONRPC],
            )
        ).create(card)
        request = SendMessageRequest(
            message=Message(
                role=Role.ROLE_USER,
                message_id="tck-complete-task-official-client",
                parts=[Part(text="Official Python SDK interoperability probe")],
            ),
            configuration=SendMessageConfiguration(return_immediately=True),
        )
        responses = [response async for response in client.send_message(request)]
        if len(responses) != 1 or not responses[0].HasField("task"):
            raise RuntimeError("official client did not receive one A2A task")
        task = responses[0].task
        state_name = TaskState.Name(task.status.state)
        if task.status.state != TaskState.TASK_STATE_COMPLETED:
            raise RuntimeError(f"project server returned {state_name}")
        print(
            f"A2A-163 OK official-client task={task.id} "
            f"context={task.context_id} state={state_name}"
        )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: official_client_probe.py PROJECT_AGENT_BASE_URL")
    asyncio.run(run(sys.argv[1]))
