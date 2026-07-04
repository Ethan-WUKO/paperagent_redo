ALTER TABLE agent_messages
    ADD COLUMN tool_call_id VARCHAR(128) NULL AFTER tool_calls_json;
