import type { LucideIcon } from 'lucide-react';
import { ChartLine, Gamepad2, Server, SlidersHorizontal, Users } from 'lucide-react';
import type { ViewName } from '../types';

interface SidebarProps {
  view: ViewName;
  serverName: string;
}
interface NavItem {
  view: ViewName;
  label: string;
  icon: LucideIcon;
}

const NAV_ITEMS: readonly NavItem[] = [
  { view: 'overview', label: 'Overview', icon: ChartLine },
  { view: 'users', label: 'Users', icon: Users },
  { view: 'games', label: 'Games', icon: Gamepad2 },
  { view: 'controllers', label: 'Controllers', icon: SlidersHorizontal },
];

export default function Sidebar({ view, serverName }: SidebarProps) {
  return (
    <aside
      className="flex w-full shrink-0 flex-col gap-5 border-b border-white/10 bg-slate-900/80 p-4 md:sticky md:top-0 md:h-screen md:w-56 md:border-r md:border-b-0 md:p-5"
      aria-label="Main navigation"
    >
      <div className="flex items-center gap-3 px-2 py-2">
        <div className="rounded-xl bg-sky-400/10 p-2.5">
          <Server aria-hidden="true" className="size-5 text-sky-400" />
        </div>
        <div>
          <span className="text-xl font-bold tracking-tight">Kaillux</span>
          <p className="text-xs text-slate-400">Admin console</p>
        </div>
      </div>
      <nav aria-label="Admin pages">
        <ul className="grid list-none grid-cols-2 gap-1 md:grid-cols-1">
          {NAV_ITEMS.map((item) => {
            const active = item.view === view;
            const Icon = item.icon;
            return (
              <li key={item.view}>
                <a
                  href={`#${item.view}`}
                  aria-current={active ? 'page' : undefined}
                  className={`flex min-h-11 items-center gap-2 rounded-lg px-3 py-3 text-sm font-medium transition-colors ${active ? 'bg-sky-400/10 text-sky-300' : 'text-slate-400 hover:bg-white/5 hover:text-slate-50'}`}
                >
                  <Icon aria-hidden="true" className="size-4 shrink-0" />
                  <span>{item.label}</span>
                </a>
              </li>
            );
          })}
        </ul>
      </nav>
      <div className="mt-auto hidden border-t border-white/10 px-2 pt-4 md:block">
        <p className="text-xs text-slate-500">Connected server</p>
        <p className="mt-1 break-words text-sm text-slate-300">{serverName}</p>
      </div>
    </aside>
  );
}
