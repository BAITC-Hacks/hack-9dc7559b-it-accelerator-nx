import { useQuery } from '@tanstack/react-query';
import { ping } from '@/client';
import { API_URL } from '@/lib/api';

/**
 * Стартовая страница каркаса: проверяет связку фронт → API через сгенерированный клиент.
 * Показывает три состояния (loading / error / ok) — на демо пустой экран = баг.
 */
export default function HomePage() {
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['ping'],
    queryFn: async () => {
      const response = await ping({ throwOnError: true });
      return response.data;
    },
  });

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-6 p-8">
      <header>
        <h1 className="text-3xl font-semibold tracking-tight">HackAlem AI</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          Каркас поднят. Backend: <code className="font-mono">{API_URL}</code>
        </p>
      </header>

      <section className="bg-card rounded-xl border p-5">
        <h2 className="mb-3 text-sm font-medium">Состояние API</h2>

        {isPending && <p className="text-muted-foreground text-sm">Проверяем backend…</p>}

        {isError && (
          <div className="text-destructive text-sm">
            <p className="font-medium">Backend недоступен</p>
            <p className="text-muted-foreground mt-1">
              Запусти: <code className="font-mono">docker compose up -d</code> и{' '}
              <code className="font-mono">cd backend && ./gradlew bootRun</code>
            </p>
            <p className="text-muted-foreground mt-1 text-xs">{(error as Error).message}</p>
          </div>
        )}

        {data && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">app</dt>
            <dd className="font-mono">{data.app}</dd>
            <dt className="text-muted-foreground">status</dt>
            <dd className="font-mono">{data.status}</dd>
            <dt className="text-muted-foreground">time</dt>
            <dd className="font-mono">{data.time}</dd>
          </dl>
        )}
      </section>

      <p className="text-muted-foreground text-xs">
        Дальше: страницы в <code className="font-mono">src/pages</code>, компоненты в{' '}
        <code className="font-mono">src/components</code>, клиент API — только из{' '}
        <code className="font-mono">src/client</code> (<code className="font-mono">npm run gen</code>
        ).
      </p>
    </main>
  );
}
