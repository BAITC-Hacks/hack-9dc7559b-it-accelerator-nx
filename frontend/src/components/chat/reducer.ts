import type { ConversationView, ReplyPhase } from './model';

// UI actions only: the generated SSE union will be mapped here by the live driver.
type ReplyUpdate = {
  kind: 'chunk'; runKey: string; generation: number; ordinal: number; text: string;
} | {
  kind: 'snapshot'; runKey: string; generation: number; ordinal: number;
  text: string; phase: ReplyPhase;
};

export function applyReplyUpdate(chat: ConversationView, update: ReplyUpdate): ConversationView {
  const reply = chat.reply;
  if (!reply || reply.key !== update.runKey || update.generation < reply.generation) return chat;
  if (['completed', 'cancelled', 'failed'].includes(reply.phase)) return chat;

  if (update.kind === 'chunk') {
    // A new worker generation needs an authoritative snapshot first. A gap is
    // recovery, not permission to concatenate incomplete/out-of-order text.
    if (update.generation !== reply.generation) return chat;
    if (update.ordinal <= reply.lastChunk) return chat;
    if (update.ordinal !== reply.lastChunk + 1) {
      return { ...chat, reply: { ...reply, phase: 'interrupted', notice: 'Часть ответа потеряна. Восстановите ответ.' } };
    }
  } else if (update.generation === reply.generation && update.ordinal < reply.lastChunk) {
    return chat;
  }

  return {
    ...chat,
    messages: chat.messages.map((message) => message.key !== reply.messageKey ? message : {
      ...message, text: update.kind === 'chunk' ? message.text + update.text : update.text,
    }),
    reply: {
      ...reply,
      generation: update.generation,
      lastChunk: update.ordinal,
      phase: update.kind === 'snapshot' ? update.phase : 'streaming',
      notice: null,
      retryAt: null,
    },
  };
}
