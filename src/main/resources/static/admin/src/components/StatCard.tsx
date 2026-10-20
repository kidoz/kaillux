import Card from './Card';

type StatTone = 'default' | 'success' | 'danger';

interface StatCardProps {
  label: string;
  value: string;
  tone?: StatTone;
  detail?: string;
}

const TONE_CLASSES: Record<StatTone, string> = {
  default: '',
  success: 'text-emerald-400',
  danger: 'text-red-300',
};

export default function StatCard({ label, value, tone = 'default', detail }: StatCardProps) {
  return (
    <Card>
      <div className="mb-3 text-sm font-medium text-slate-400">{label}</div>
      <div
        className={`break-words text-2xl font-semibold tracking-tight tabular-nums sm:text-3xl ${TONE_CLASSES[tone]}`}
      >
        {value}
      </div>
      {detail && <div className="mt-2 text-xs text-slate-400">{detail}</div>}
    </Card>
  );
}
