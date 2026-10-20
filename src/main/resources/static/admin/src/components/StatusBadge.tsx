interface StatusBadgeProps {
  status: string;
}

const STATUS_CLASSES: Record<string, string> = {
  playing: 'bg-green-500/20 text-green-400',
  idle: 'bg-slate-400/20 text-slate-300',
  connecting: 'bg-amber-500/20 text-amber-400',
  waiting: 'bg-sky-400/20 text-sky-300',
  synchronizing: 'bg-amber-500/20 text-amber-300',
};

export default function StatusBadge({ status }: StatusBadgeProps) {
  const tone = STATUS_CLASSES[status.toLowerCase()] ?? 'bg-white/10 text-slate-300';

  return (
    <span
      className={`inline-block rounded-full px-3 py-0.5 text-xs font-semibold capitalize ${tone}`}
    >
      {status.toLowerCase().replace(/_/g, ' ')}
    </span>
  );
}
