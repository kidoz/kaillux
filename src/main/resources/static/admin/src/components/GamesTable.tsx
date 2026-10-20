import { useState } from 'react';
import type { Game } from '../types';
import StatusBadge from './StatusBadge';
import TablePanel from './TablePanel';

interface GamesTableProps {
  games: Game[] | null;
  loading: boolean;
}

export default function GamesTable({ games, loading }: GamesTableProps) {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const query = search.trim().toLowerCase();
  const visible = (games ?? []).filter(
    (game) =>
      (!status || game.status.toUpperCase() === status) &&
      `${game.rom} ${game.owner} ${game.id}`.toLowerCase().includes(query)
  );
  return (
    <TablePanel
      noun="games"
      count={visible.length}
      total={games?.length ?? 0}
      loading={loading && games === null}
      unavailable={!loading && games === null}
      emptyMessage="No game rooms yet"
      search={{ value: search, onChange: setSearch, placeholder: 'Search ROM, owner or ID' }}
      status={{
        value: status,
        onChange: setStatus,
        options: ['WAITING', 'PLAYING', 'SYNCHRONIZING'],
      }}
    >
      <table className="data-table" aria-label="Games table">
        <thead>
          <tr>
            {['ID', 'ROM name', 'Owner', 'Status', 'Players'].map((label) => (
              <th scope="col" key={label}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visible.map((game) => (
            <tr key={game.id}>
              <td className="text-slate-400 tabular-nums">{game.id}</td>
              <td className="min-w-52 max-w-lg break-words font-semibold">{game.rom}</td>
              <td className="min-w-32 max-w-64 break-words">{game.owner}</td>
              <td>
                <StatusBadge status={game.status} />
              </td>
              <td className="tabular-nums">{game.players}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </TablePanel>
  );
}
