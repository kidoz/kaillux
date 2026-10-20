import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AuthError,
  basicAuthorization,
  fetchControllers,
  fetchGames,
  fetchServerInfo,
  fetchUsers,
  kickUser,
} from './api';
import ControllersTable from './components/ControllersTable';
import ErrorBanner from './components/ErrorBanner';
import GamesTable from './components/GamesTable';
import KickDialog from './components/KickDialog';
import LoginScreen from './components/LoginScreen';
import PageHeader from './components/PageHeader';
import Sidebar from './components/Sidebar';
import StatCard from './components/StatCard';
import UsersTable from './components/UsersTable';
import type { Controller, Game, ServerInfo, User, ViewName } from './types';

const VIEWS: Record<ViewName, { title: string; description: string }> = {
  overview: { title: 'Overview', description: 'Server health, capacity and connection activity.' },
  users: {
    title: 'Active users',
    description: 'Find connected players and manage their sessions.',
  },
  games: {
    title: 'Game rooms',
    description: 'See what is being played and which rooms are waiting.',
  },
  controllers: {
    title: 'Kaillera controllers',
    description: 'Protocol support and connected client activity.',
  },
};
const REFRESH_INTERVAL_MS = 10000;
const CREDENTIALS_KEY = 'kaillux-admin-basic';
type SessionState = 'checking' | 'authenticated' | 'anonymous';

function currentView(): ViewName {
  const value = window.location.hash.slice(1);
  return value === 'users' || value === 'games' || value === 'controllers' ? value : 'overview';
}

function formatUptime(minutes: number): string {
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  return [days ? `${days}d` : '', hours ? `${hours}h` : '', `${minutes % 60}m`]
    .filter(Boolean)
    .join(' ');
}

export default function App() {
  const [session, setSession] = useState<SessionState>('checking');
  const [authHeader, setAuthHeader] = useState<string | null>(null);
  const [loginError, setLoginError] = useState('');
  const [signingIn, setSigningIn] = useState(false);
  const [view, setView] = useState<ViewName>(currentView);
  const [serverInfo, setServerInfo] = useState<ServerInfo | null>(null);
  const [users, setUsers] = useState<User[] | null>(null);
  const [games, setGames] = useState<Game[] | null>(null);
  const [controllers, setControllers] = useState<Controller[] | null>(null);
  const [refreshing, setRefreshing] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [kickTarget, setKickTarget] = useState<User | null>(null);
  const [kicking, setKicking] = useState(false);
  const [kickError, setKickError] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  const sessionEpoch = useRef(0);

  const signOut = useCallback((message = '') => {
    sessionEpoch.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    sessionStorage.removeItem(CREDENTIALS_KEY);
    setAuthHeader(null);
    setSession('anonymous');
    setLoginError(message);
    setServerInfo(null);
    setUsers(null);
    setGames(null);
    setControllers(null);
    setLastUpdated(null);
    setLoadError(null);
    setNotice(null);
    setKickTarget(null);
    setKickError('');
    setKicking(false);
    setRefreshing(true);
  }, []);

  const refresh = useCallback(
    async (force = false) => {
      if (!authHeader || (abortRef.current && !force)) return;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setRefreshing(true);
      try {
        const [info, nextUsers, nextGames, nextControllers] = await Promise.all([
          fetchServerInfo(authHeader, controller.signal),
          fetchUsers(authHeader, controller.signal),
          fetchGames(authHeader, controller.signal),
          fetchControllers(authHeader, controller.signal),
        ]);
        if (controller.signal.aborted) return;
        if (info) setServerInfo(info);
        if (nextUsers) setUsers(nextUsers);
        if (nextGames) setGames(nextGames);
        if (nextControllers) setControllers(nextControllers);
        const failed = [
          !info && 'server status',
          !nextUsers && 'users',
          !nextGames && 'games',
          !nextControllers && 'controllers',
        ].filter(Boolean);
        setLoadError(
          failed.length
            ? `Could not refresh ${failed.join(', ')}. Previously loaded data may be out of date. Retrying every 10 seconds, or use Refresh to try now.`
            : null
        );
        if (!failed.length) setLastUpdated(new Date());
      } catch (error) {
        if (controller.signal.aborted) return;
        if (error instanceof AuthError) signOut('Your session has expired. Please sign in again.');
        else setLoadError('Unable to refresh server data. Retrying every 10 seconds.');
      } finally {
        if (abortRef.current === controller) {
          abortRef.current = null;
          setRefreshing(false);
        }
      }
    },
    [authHeader, signOut]
  );

  useEffect(() => {
    const onHashChange = () => setView(currentView());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    document.title =
      session === 'authenticated'
        ? `${VIEWS[view].title} · Kaillux Admin`
        : 'Sign in · Kaillux Admin';
  }, [view, session]);

  useEffect(() => {
    if (!notice) return;
    const id = window.setTimeout(() => setNotice(null), 6000);
    return () => window.clearTimeout(id);
  }, [notice]);

  useEffect(() => {
    const stored = sessionStorage.getItem(CREDENTIALS_KEY);
    if (!stored) {
      setSession('anonymous');
      return;
    }
    const controller = new AbortController();
    void (async () => {
      try {
        const info = await fetchServerInfo(stored, controller.signal);
        if (controller.signal.aborted) return;
        if (!info) {
          signOut('Unable to reach the server. Please try again.');
          return;
        }
        setAuthHeader(stored);
        setServerInfo(info);
        setSession('authenticated');
      } catch (error) {
        if (controller.signal.aborted) return;
        signOut(
          error instanceof AuthError
            ? 'Your session has expired. Please sign in again.'
            : 'Unable to reach the server. Please try again.'
        );
      }
    })();
    return () => controller.abort();
  }, [signOut]);

  useEffect(() => {
    if (session !== 'authenticated' || !authHeader) return;
    void refresh();
    const intervalId = window.setInterval(() => void refresh(), REFRESH_INTERVAL_MS);
    return () => {
      window.clearInterval(intervalId);
      abortRef.current?.abort();
      abortRef.current = null;
    };
  }, [session, authHeader, refresh]);

  const handleLogin = async (username: string, password: string) => {
    if (signingIn) return;
    setSigningIn(true);
    setLoginError('');
    try {
      const authorization = basicAuthorization(username, password);
      const info = await fetchServerInfo(authorization);
      if (!info) {
        setLoginError('Unable to reach the server. Check the connection and try again.');
        return;
      }
      sessionStorage.setItem(CREDENTIALS_KEY, authorization);
      setAuthHeader(authorization);
      setServerInfo(info);
      setSession('authenticated');
    } catch (error) {
      setLoginError(
        error instanceof AuthError
          ? 'Username or password was rejected. Please try again.'
          : 'Unable to reach the server. Please try again.'
      );
    } finally {
      setSigningIn(false);
    }
  };

  const handleKick = async (reason: string) => {
    if (!kickTarget || !authHeader || kicking) return;
    const user = kickTarget;
    const epoch = sessionEpoch.current;
    setKicking(true);
    setKickError('');
    try {
      const response = await kickUser(user.id, reason, authHeader);
      if (epoch !== sessionEpoch.current) return;
      if (response.notFound || response.result?.success) {
        setKickTarget(null);
        setNotice(
          response.notFound
            ? `${user.name} has already disconnected.`
            : `${user.name} was kicked from the server.`
        );
        void refresh(true);
      } else
        setKickError(response.result?.message ?? 'Unable to kick this user. Please try again.');
    } catch (error) {
      if (epoch !== sessionEpoch.current) return;
      if (error instanceof AuthError) signOut('Your session has expired. Please sign in again.');
      else
        setKickError(
          error instanceof Error && error.name === 'AbortError'
            ? 'The request timed out. Check whether the user is still connected before retrying.'
            : 'Unable to kick this user. Please try again.'
        );
    } finally {
      if (epoch === sessionEpoch.current) setKicking(false);
    }
  };

  if (session !== 'authenticated') {
    return (
      <LoginScreen
        error={loginError}
        pending={signingIn}
        checking={session === 'checking'}
        onSubmit={handleLogin}
      />
    );
  }

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      <button
        type="button"
        onClick={() => {
          document.getElementById('main-content')?.focus();
        }}
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-lg focus:bg-sky-400 focus:p-3 focus:text-slate-950"
      >
        Skip to content
      </button>
      <Sidebar view={view} serverName={serverInfo?.serverName ?? 'Kaillera server'} />
      <main
        id="main-content"
        tabIndex={-1}
        className="min-w-0 flex-1 p-4 sm:p-6 lg:p-8"
        aria-label="Dashboard content"
      >
        <div className="mx-auto max-w-7xl">
          <PageHeader
            title={VIEWS[view].title}
            description={VIEWS[view].description}
            refreshing={refreshing}
            lastUpdated={lastUpdated}
            stale={Boolean(loadError)}
            onRefresh={() => void refresh()}
            onSignOut={() => signOut()}
          />
          {loadError && <ErrorBanner message={loadError} />}
          {notice && (
            <p
              role="status"
              className="mb-4 rounded-lg border border-emerald-400/20 bg-emerald-400/10 p-4 text-sm text-emerald-200"
            >
              {notice}
            </p>
          )}
          {view === 'overview' && (
            <>
              <section
                className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
                aria-label="Server statistics"
              >
                <StatCard
                  label="Server status"
                  value={serverInfo ? (serverInfo.running ? 'Online' : 'Offline') : '—'}
                  tone={serverInfo?.running ? 'success' : 'danger'}
                  detail={serverInfo?.serverName}
                />
                <StatCard
                  label="Active users"
                  value={serverInfo ? String(serverInfo.userCount) : '—'}
                  detail={serverInfo ? `Capacity: ${serverInfo.maxUsers} users` : undefined}
                />
                <StatCard
                  label="Active games"
                  value={serverInfo ? String(serverInfo.gameCount) : '—'}
                  detail={
                    serverInfo
                      ? serverInfo.maxGames > 0
                        ? `Capacity: ${serverInfo.maxGames} rooms`
                        : 'No room limit'
                      : undefined
                  }
                />
                <StatCard
                  label="Uptime"
                  value={serverInfo ? formatUptime(serverInfo.uptimeMinutes) : '—'}
                  detail="Since the last server start"
                />
              </section>
              <h2 className="mb-3 text-lg font-semibold">Connection activity</h2>
              <section
                className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
                aria-label="Connection statistics"
              >
                <StatCard
                  label="Connection attempts"
                  value={serverInfo?.stats.requestCount.toLocaleString() ?? '—'}
                  detail="Total since server start"
                />
                <StatCard
                  label="Successful connections"
                  value={serverInfo?.stats.connectCount.toLocaleString() ?? '—'}
                  detail="Total since server start"
                />
                <StatCard
                  label="Protocol errors"
                  value={serverInfo?.stats.protocolErrors.toLocaleString() ?? '—'}
                  tone={serverInfo?.stats.protocolErrors ? 'danger' : 'default'}
                  detail="Total since server start"
                />
                <StatCard
                  label="Thread pool"
                  value={
                    serverInfo
                      ? `${serverInfo.threadPool.active} / ${serverInfo.threadPool.poolSize}`
                      : '—'
                  }
                  detail="Active threads / pool size"
                />
              </section>
              {serverInfo && (
                <p className="mt-6 text-xs text-slate-400">
                  Version {serverInfo.version} · Build {serverInfo.build} · UDP port{' '}
                  {serverInfo.connectPort}
                </p>
              )}
            </>
          )}
          {view === 'users' && (
            <section aria-labelledby="view-title">
              <UsersTable
                users={users}
                loading={refreshing}
                onKick={(user) => {
                  setKickError('');
                  setKickTarget(user);
                }}
              />
            </section>
          )}
          {view === 'games' && (
            <section aria-labelledby="view-title">
              <GamesTable games={games} loading={refreshing} />
            </section>
          )}
          {view === 'controllers' && (
            <section aria-labelledby="view-title">
              <ControllersTable controllers={controllers} loading={refreshing} />
            </section>
          )}
        </div>
      </main>
      {kickTarget && (
        <KickDialog
          user={kickTarget}
          pending={kicking}
          error={kickError}
          onCancel={() => setKickTarget(null)}
          onConfirm={(reason) => void handleKick(reason)}
        />
      )}
    </div>
  );
}
