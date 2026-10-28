export function RecallNotice({ role }: { role: string }) {
  return <div className="flex justify-center py-3" data-message-recalled="true">
    <span className="rounded-full bg-muted/60 px-4 py-1.5 text-xs text-muted-foreground">
      {role === "ASSISTANT" ? "此条消息已被对方撤回" : "该条消息已撤回"}
    </span>
  </div>;
}
