import { TriangleAlert } from 'lucide-react';

interface ErrorBannerProps {
  message: string;
}

export default function ErrorBanner({ message }: ErrorBannerProps) {
  return (
    <div
      className="mb-4 flex items-start gap-3 rounded-lg border border-amber-400/20 bg-amber-400/10 p-4 text-sm leading-relaxed text-amber-200"
      role="alert"
    >
      <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <span>{message}</span>
    </div>
  );
}
