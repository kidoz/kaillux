import { useLayoutEffect, useRef, useState } from 'react';
import type { User } from '../types';
import Button from './Button';

interface KickDialogProps {
  user: User;
  pending: boolean;
  error: string;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

export default function KickDialog({ user, pending, error, onCancel, onConfirm }: KickDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const [reason, setReason] = useState('Kicked by administrator');

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    const previouslyFocused = document.activeElement;
    dialog?.showModal();
    cancelRef.current?.focus();
    return () => {
      dialog?.close();
      if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected)
        previouslyFocused.focus();
    };
  }, []);

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="kick-title"
      aria-describedby="kick-description"
      onCancel={(event) => {
        event.preventDefault();
        if (!pending) onCancel();
      }}
      className="fixed inset-0 m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-2xl border border-white/15 bg-slate-900 p-6 text-slate-50 shadow-2xl"
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!pending) onConfirm(reason.trim());
        }}
      >
        <h2 id="kick-title" className="text-xl font-semibold">
          Kick user?
        </h2>
        <p
          id="kick-description"
          className="mt-3 break-words text-sm leading-relaxed text-slate-300"
        >
          <strong className="text-slate-50">{user.name}</strong> (ID {user.id}) will be disconnected
          from the server and their current game.
        </p>
        <label className="mt-5 flex flex-col gap-2 text-sm text-slate-300">
          Reason sent to user
          <input
            className="field w-full"
            name="reason"
            value={reason}
            required
            disabled={pending}
            onChange={(event) => setReason(event.target.value)}
          />
        </label>
        {error && (
          <p className="mt-4 text-sm text-red-300" role="alert">
            {error}
          </p>
        )}
        <div className="mt-6 flex justify-end gap-3">
          <Button ref={cancelRef} variant="ghost" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" variant="danger" disabled={pending || !reason.trim()}>
            {pending ? 'Kicking…' : 'Kick user'}
          </Button>
        </div>
      </form>
    </dialog>
  );
}
