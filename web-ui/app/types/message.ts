import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

/** Host provenance, not an instruction role. Matches ai/.../OrbisEventMetadata.kt. */
export interface OrbisEventMetadata {
  recordId: string;
  source: string;
  eventId: string;
  receivedAt: number;
  read?: boolean;
  collapsed?: boolean;
  occurredAt?: number | null;
}

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  usage?: TokenUsage | null;
  translation?: string | null;
  orbisEvent?: OrbisEventMetadata | null;
}
