import { LogOut, RefreshCw } from 'lucide-react';
import Button from './Button';

interface PageHeaderProps {
  title: string;
  description: string;
  refreshing: boolean;
  lastUpdated: Date | null;
  stale: boolean;
  onRefresh: () => void;
  onSignOut: () => void;
}

export default function PageHeader({
  title,
  description,
  refreshing,
  lastUpdated,
  stale,
  onRefresh,
  onSignOut,
}: PageHeaderProps) {
  return (
    <header className="mb-7 flex flex-wrap items-start justify-between gap-5">
      <div>
        <p className="mb-2 text-xs font-medium tracking-widest text-sky-300 uppercase">
          Server administration
        </p>
        <h1 id="view-title" className="text-2xl font-semibold tracking-tight sm:text-3xl">
          {title}
        </h1>
        <p className="mt-2 text-sm text-slate-400">{description}</p>
      </div>
      <div className="flex w-full flex-col gap-2 sm:w-auto sm:items-end">
        <div className="flex gap-2">
          <Button
            onClick={onRefresh}
            disabled={refreshing}
            aria-busy={refreshing}
            aria-label="Refresh dashboard data"
          >
            <RefreshCw
              aria-hidden="true"
              className={`size-4 ${refreshing ? 'animate-spin' : ''}`}
            />
            <span>{refreshing ? 'Refreshing…' : 'Refresh'}</span>
          </Button>
          <Button variant="ghost" onClick={onSignOut}>
            <LogOut aria-hidden="true" className="size-4" />
            <span>Sign out</span>
          </Button>
        </div>
        <p className={`text-xs ${stale ? 'text-amber-300' : 'text-slate-400'}`}>
          {lastUpdated
            ? `Last complete update ${lastUpdated.toLocaleTimeString()}`
            : stale
              ? 'Some data is unavailable'
              : 'Fetching server data…'}
          {' · Every 10s'}
        </p>
      </div>
    </header>
  );
}
