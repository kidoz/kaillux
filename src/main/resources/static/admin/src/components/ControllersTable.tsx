import type { Controller } from '../types';
import TablePanel from './TablePanel';

interface ControllersTableProps {
  controllers: Controller[] | null;
  loading: boolean;
}

export default function ControllersTable({ controllers, loading }: ControllersTableProps) {
  return (
    <TablePanel
      noun="controllers"
      count={controllers?.length ?? 0}
      total={controllers?.length ?? 0}
      loading={loading && controllers === null}
      unavailable={!loading && controllers === null}
      emptyMessage="No controllers available"
    >
      <table className="data-table" aria-label="Controllers table">
        <thead>
          <tr>
            {['Version', 'Buffer size', 'Active clients', 'Protocols'].map((label) => (
              <th scope="col" key={label}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {controllers?.map((controller) => (
            <tr key={`${controller.version}-${controller.bufferSize}`}>
              <td className="font-semibold">{controller.version}</td>
              <td className="whitespace-nowrap tabular-nums">
                {controller.bufferSize.toLocaleString()}{' '}
                <span className="text-slate-400">bytes</span>
              </td>
              <td className="tabular-nums">{controller.numClients}</td>
              <td className="min-w-48">{controller.clientTypes.join(', ') || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </TablePanel>
  );
}
