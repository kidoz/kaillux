import type { ComponentPropsWithRef } from 'react';

type ButtonVariant = 'primary' | 'danger' | 'ghost';
type ButtonSize = 'md' | 'sm';

interface ButtonProps extends ComponentPropsWithRef<'button'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: 'bg-sky-400 text-slate-950 hover:bg-sky-300',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'border border-white/15 bg-transparent text-slate-200 hover:bg-white/5',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  md: 'min-h-11 px-4 py-2 text-sm',
  sm: 'min-h-11 sm:min-h-9 px-3 py-1.5 text-xs',
};

export default function Button({
  variant = 'primary',
  size = 'md',
  type = 'button',
  className = '',
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      className={`inline-flex cursor-pointer items-center justify-center gap-2 rounded-lg font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-400 disabled:cursor-not-allowed disabled:opacity-50 ${VARIANT_CLASSES[variant]} ${SIZE_CLASSES[size]} ${className}`}
      {...props}
    />
  );
}
