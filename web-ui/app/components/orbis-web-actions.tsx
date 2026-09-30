import * as React from "react";
import { FileArchive, MessageSquare, Palette, Telescope, Smartphone } from "lucide-react";
import api from "~/services/api";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "~/components/ui/dropdown-menu";
import { OrbisConstellation } from "~/components/logo";
import { useTheme, type ThemeMode } from "~/components/theme-provider";
import { OrbisImportDialog } from "~/components/deepseek-import-dialog";
import { OrbisContextDialog } from "~/components/orbis-context-dialog";

export function OrbisWebActions({ conversationId, appearanceOpen: appearance, onAppearanceOpenChange: setAppearance, onImported, onContextCommitted }: {
  conversationId: string | null; onImported: () => void; onContextCommitted: () => void;
  appearanceOpen: boolean; onAppearanceOpenChange: (open: boolean) => void;
}) {
  const [imports, setImports] = React.useState(false);
  const [context, setContext] = React.useState(false);
  const [phoneInfo, setPhoneInfo] = React.useState(false);
  const { settings } = useCurrentAssistant();
  const { theme, setTheme, colorTheme, setColorTheme } = useTheme();
  const [nickname, setNickname] = React.useState("");
  const [saving, setSaving] = React.useState(false);
  const [message, setMessage] = React.useState<string | null>(null);
  React.useEffect(() => {
    if (appearance) { setNickname(settings?.displaySetting.userNickname ?? ""); setMessage(null); }
  }, [appearance]);
  const protectedAccess = settings?.webImportEnabled === true;
  const save = async () => {
    setSaving(true); setMessage(null);
    try {
      await api.post("orbis/profile", { nickname: nickname.trim() }, { retry: 0 });
      setMessage("昵称已保存，手机与 Web 会同步显示。");
    } catch { setMessage("未确认保存成功，请检查连接后重试。"); }
    finally { setSaving(false); }
  };
  return <>
    <Button variant="outline" size="sm" className="hidden sm:inline-flex" onClick={() => setImports(true)}><FileArchive className="size-4" />导入聊天</Button>
    <DropdownMenu>
      <DropdownMenuTrigger asChild><Button variant="ghost" className="h-10 px-2 text-primary" aria-label="北斗 · Orbis 功能"><OrbisConstellation className="size-auto h-9 w-16" /></Button></DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56 rounded-2xl">
        <DropdownMenuLabel>北斗 · Orbis</DropdownMenuLabel><DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => document.querySelector<HTMLTextAreaElement>("textarea")?.focus()}><MessageSquare />当前聊天</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => setImports(true)}><FileArchive />导入聊天记录</DropdownMenuItem>
        <DropdownMenuItem disabled={!conversationId || !protectedAccess} onSelect={() => setContext(true)}><Telescope />上下文 · 手动整理</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => setAppearance(true)}><Palette />外观与昵称</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => setPhoneInfo(true)}><Smartphone />手机专属能力 · 说明</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
    <OrbisImportDialog open={imports} onOpenChange={setImports} onImported={onImported} />
    <OrbisContextDialog open={context} onOpenChange={setContext} conversationId={conversationId} onCommitted={onContextCommitted} />
    <Dialog open={phoneInfo} onOpenChange={setPhoneInfo}><DialogContent className="max-w-md rounded-3xl">
      <DialogHeader><DialogTitle>与你的手机一起工作</DialogTitle><DialogDescription>Web 是 Orbis 的聊天工作台，不是手机全部功能的镜像。</DialogDescription></DialogHeader>
      <p className="text-sm leading-relaxed text-muted-foreground">这里可以聊天、导入 DeepSeek / RikkaHub ZIP 或 Codex 原始会话 JSONL、手动整理当前上下文、调整外观与昵称。哨兵、屏幕、语音以及设备权限仍在手机端管理；网页不会自动开启这些能力。</p>
      <p className="text-xs text-muted-foreground">Orbis 基于开源 RikkaHub 构建；上游许可证与署名保留。</p>
    </DialogContent></Dialog>
    <Dialog open={appearance} onOpenChange={setAppearance}><DialogContent className="max-w-md rounded-3xl">
      <DialogHeader><DialogTitle>外观与昵称</DialogTitle><DialogDescription>配色只保存在这台浏览器；昵称同步到手机。</DialogDescription></DialogHeader>
      <div className="space-y-5">
        <div className="flex flex-wrap gap-2">{([["light", "浅色"], ["dark", "深色"], ["system", "跟随系统"]] as const).map(([value, label]) =>
          <Button key={value} variant={theme === value ? "default" : "outline"} onClick={() => setTheme(value as ThemeMode)}>{label}</Button>)}
        </div>
        <div className="space-y-2">
          <p className="text-sm">主题风格</p>
          <div className="flex flex-wrap gap-2">
            <Button variant={colorTheme === "orbis" ? "default" : "outline"} aria-pressed={colorTheme === "orbis"} onClick={() => setColorTheme("orbis")}>Orbis</Button>
            <Button variant={colorTheme === "deepseek" ? "default" : "outline"} aria-pressed={colorTheme === "deepseek"} onClick={() => setColorTheme("deepseek")}>DeepSeek 风格</Button>
          </div>
          <p className="text-xs text-muted-foreground">两种风格均支持浅色与深色，只改变显示。DeepSeek 风格并非官方客户端；模型、工具和按钮位置不变。以前保存的自定义 CSS 仍可在侧栏调色板中编辑并重新应用。</p>
        </div>
        <div className="space-y-2"><label htmlFor="orbis-nickname" className="text-sm">你的昵称</label>
          <Input id="orbis-nickname" value={nickname} maxLength={80} disabled={!protectedAccess || saving} onChange={(event) => setNickname(event.target.value)} />
          {!protectedAccess && <p className="text-xs text-muted-foreground">修改昵称、导入及整理上下文需要先在手机开启 Web 密码保护，再登录。</p>}
          <Button onClick={() => void save()} disabled={!protectedAccess || saving || nickname.trim().length > 80}>{saving ? "正在保存…" : "保存昵称"}</Button>
          {message && <p role="status" className="text-sm text-muted-foreground">{message}</p>}
        </div>
      </div>
    </DialogContent></Dialog>
  </>;
}
