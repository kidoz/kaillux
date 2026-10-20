import { LoaderCircle, Server } from 'lucide-react';
import type { FormEvent } from 'react';
import { useState } from 'react';
import Button from './Button';

interface LoginScreenProps {
  error: string;
  pending: boolean;
  checking: boolean;
  onSubmit: (username: string, password: string) => Promise<void>;
}

const INPUT_CLASSES = 'field w-full disabled:opacity-50';

export default function LoginScreen({ error, pending, checking, onSubmit }: LoginScreenProps) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!pending && !checking && username.trim()) void onSubmit(username.trim(), password);
  };

  return (
    <div className="flex min-h-screen items-center justify-center p-4 sm:p-8">
      <form
        onSubmit={handleSubmit}
        aria-busy={pending || checking}
        aria-describedby={error ? 'login-error' : undefined}
        className="flex w-full max-w-sm flex-col gap-4 rounded-2xl border border-white/10 bg-slate-800/70 p-8 shadow-[0_8px_32px_0_rgba(0,0,0,0.37)]"
      >
        <Server aria-hidden="true" className="size-8 text-sky-400" />
        <h1 className="text-2xl font-bold">Kaillux Admin</h1>
        <p className="text-slate-400">Sign in with the server admin account.</p>
        <label className="flex flex-col gap-1 text-sm text-slate-400">
          Username
          <input
            type="text"
            name="username"
            autoComplete="username"
            required
            disabled={pending || checking}
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className={INPUT_CLASSES}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-slate-400">
          Password
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            disabled={pending || checking}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className={INPUT_CLASSES}
          />
        </label>
        <p id="login-error" role="alert" className="min-h-5 text-sm text-red-300">
          {error}
        </p>
        <Button
          type="submit"
          className="w-full"
          disabled={pending || checking || !username.trim() || !password}
        >
          {(pending || checking) && (
            <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
          )}
          {checking ? 'Checking session…' : pending ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  );
}
