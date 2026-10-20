import { useState } from 'react';
import type { User } from '../types';
import Button from './Button';
import StatusBadge from './StatusBadge';
import TablePanel from './TablePanel';

interface UsersTableProps {
  users: User[] | null;
  loading: boolean;
  onKick: (user: User) => void;
}

export default function UsersTable({ users, loading, onKick }: UsersTableProps) {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const query = search.trim().toLowerCase();
  const visible = (users ?? []).filter(
    (user) =>
      (!status || user.status.toUpperCase() === status) &&
      `${user.name} ${user.id} ${user.address}`.toLowerCase().includes(query)
  );
  return (
    <TablePanel
      noun="users"
      count={visible.length}
      total={users?.length ?? 0}
      loading={loading && users === null}
      unavailable={!loading && users === null}
      emptyMessage="No users connected"
      search={{ value: search, onChange: setSearch, placeholder: 'Search name, ID or address' }}
      status={{ value: status, onChange: setStatus, options: ['CONNECTING', 'IDLE', 'PLAYING'] }}
    >
      <table className="data-table" aria-label="Users table">
        <thead>
          <tr>
            {['ID', 'Name', 'Status', 'Connection', 'Ping', 'Address', 'Actions'].map((label) => (
              <th scope="col" key={label}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visible.map((user) => (
            <tr key={user.id}>
              <td className="text-slate-400 tabular-nums">{user.id}</td>
              <td className="min-w-36 max-w-80 break-words font-semibold">{user.name}</td>
              <td>
                <StatusBadge status={user.status} />
              </td>
              <td className="capitalize">{user.connectionType.toLowerCase()}</td>
              <td className="whitespace-nowrap tabular-nums">
                {user.ping} <span className="text-slate-400">ms</span>
              </td>
              <td className="font-mono text-xs text-slate-300">{user.address}</td>
              <td>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-red-300"
                  onClick={() => onKick(user)}
                  aria-label={`Kick user ${user.name}`}
                >
                  Kick
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </TablePanel>
  );
}
