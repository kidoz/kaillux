import type { ReactNode } from 'react';

interface CardProps {
  children: ReactNode;
  className?: string;
}

export default function Card({ children, className = '' }: CardProps) {
  return (
    <div
      className={`min-w-0 rounded-2xl border border-white/10 bg-slate-800/60 p-4 shadow-sm sm:p-6 ${className}`}
    >
      {children}
    </div>
  );
}
