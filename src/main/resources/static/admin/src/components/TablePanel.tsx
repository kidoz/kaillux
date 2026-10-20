import { LoaderCircle, Search, SearchX, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import Button from './Button';
import Card from './Card';

interface TablePanelProps {
  noun: string;
  count: number;
  total: number;
  loading: boolean;
  unavailable: boolean;
  emptyMessage: string;
  children: ReactNode;
  search?: { value: string; onChange: (value: string) => void; placeholder: string };
  status?: { value: string; onChange: (value: string) => void; options: string[] };
}

export default function TablePanel({
  noun,
  count,
  total,
  loading,
  unavailable,
  emptyMessage,
  children,
  search,
  status,
}: TablePanelProps) {
  const filtered = Boolean(search?.value || status?.value);
  const StateIcon = loading ? LoaderCircle : unavailable ? TriangleAlert : SearchX;
  return (
    <Card>
      <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <p className="text-sm text-slate-400" role="status">
          {loading
            ? `Loading ${noun}…`
            : unavailable
              ? `${noun} unavailable`
              : `${count} of ${total} ${noun}`}
        </p>
        {search && (
          <div className="flex flex-col gap-2 sm:flex-row">
            <label className="relative min-w-0 sm:w-72">
              <span className="sr-only">Search {noun}</span>
              <Search
                aria-hidden="true"
                className="pointer-events-none absolute top-3.5 left-3 size-4 text-slate-400"
              />
              <input
                className="field w-full pl-9"
                type="search"
                value={search.value}
                placeholder={search.placeholder}
                onChange={(event) => search.onChange(event.target.value)}
              />
            </label>
            {status && (
              <label>
                <span className="sr-only">Filter {noun} by status</span>
                <select
                  className="field w-full"
                  value={status.value}
                  onChange={(event) => status.onChange(event.target.value)}
                >
                  <option value="">All statuses</option>
                  {status.options.map((option) => (
                    <option key={option} value={option}>
                      {option.charAt(0) + option.slice(1).toLowerCase()}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
        )}
      </div>
      {loading || unavailable || count === 0 ? (
        <div className="rounded-xl border border-dashed border-white/10 px-4 py-14 text-center">
          <StateIcon
            aria-hidden="true"
            className={`mx-auto mb-3 size-7 text-slate-400 ${loading ? 'animate-spin' : ''}`}
          />
          <p className="font-medium">
            {loading
              ? `Loading ${noun}…`
              : unavailable
                ? `Unable to load ${noun}`
                : filtered
                  ? `No matching ${noun}`
                  : emptyMessage}
          </p>
          <p className="mt-2 text-sm text-slate-400">
            {loading
              ? 'Fetching the latest server data.'
              : unavailable
                ? 'Use Refresh to try again.'
                : filtered
                  ? 'Try another search or clear the filters.'
                  : 'This list updates automatically every 10 seconds.'}
          </p>
          {filtered && !loading && !unavailable && (
            <Button
              variant="ghost"
              className="mt-4"
              onClick={() => {
                search?.onChange('');
                status?.onChange('');
              }}
            >
              Clear filters
            </Button>
          )}
        </div>
      ) : (
        <section
          className="overflow-x-auto rounded-lg focus-visible:outline-sky-400"
          aria-label={`${noun} table, scroll horizontally for more columns`}
          // biome-ignore lint/a11y/noNoninteractiveTabindex: Keyboard users need to scroll wide tables.
          tabIndex={0}
        >
          {children}
          <p className="mt-3 text-xs text-slate-400 lg:hidden">
            Scroll horizontally to see all columns.
          </p>
        </section>
      )}
    </Card>
  );
}
