import { z } from 'zod';

// Zod schemas for runtime validation
export const ServerStatsSchema = z.object({
  requestCount: z.number(),
  connectCount: z.number(),
  protocolErrors: z.number(),
  deniedFull: z.number(),
  deniedOther: z.number(),
});

export const ThreadPoolSchema = z.object({
  active: z.number(),
  poolSize: z.number(),
  maxPoolSize: z.number(),
  taskCount: z.number(),
});

export const ServerInfoSchema = z.object({
  serverName: z.string(),
  version: z.string(),
  build: z.number(),
  running: z.boolean(),
  connectPort: z.number(),
  uptimeMinutes: z.number(),
  userCount: z.number(),
  maxUsers: z.number(),
  gameCount: z.number(),
  maxGames: z.number(),
  stats: ServerStatsSchema,
  threadPool: ThreadPoolSchema,
});

// The server reports status and connection types as display strings such as
// "Idle" and "Lan", so those fields are plain strings rather than enums.

export const UserSchema = z.object({
  id: z.number(),
  name: z.string(),
  status: z.string(),
  connectionType: z.string(),
  ping: z.number(),
  address: z.string(),
  connectTime: z.number(),
});

export const GameSchema = z.object({
  id: z.number(),
  rom: z.string(),
  owner: z.string(),
  status: z.string(),
  players: z.number(),
});

export const ControllerSchema = z.object({
  version: z.string(),
  bufferSize: z.number(),
  numClients: z.number(),
  clientTypes: z.array(z.string()),
});

export const UsersArraySchema = z.array(UserSchema);
export const GamesArraySchema = z.array(GameSchema);
export const ControllersArraySchema = z.array(ControllerSchema);

// Action result schema for admin operations
export const ActionResultSchema = z.object({
  success: z.boolean(),
  message: z.string().nullable().optional(),
});

// Inferred TypeScript types from schemas
export type ServerStats = z.infer<typeof ServerStatsSchema>;
export type ThreadPool = z.infer<typeof ThreadPoolSchema>;
export type ServerInfo = z.infer<typeof ServerInfoSchema>;
export type User = z.infer<typeof UserSchema>;
export type Game = z.infer<typeof GameSchema>;
export type Controller = z.infer<typeof ControllerSchema>;
export type ActionResult = z.infer<typeof ActionResultSchema>;

export type ViewName = 'overview' | 'users' | 'games' | 'controllers';

// Validation helpers
export function validateServerInfo(data: unknown): ServerInfo | null {
  const result = ServerInfoSchema.safeParse(data);
  return result.success ? result.data : null;
}

export function validateUsers(data: unknown): User[] | null {
  const result = UsersArraySchema.safeParse(data);
  return result.success ? result.data : null;
}

export function validateGames(data: unknown): Game[] | null {
  const result = GamesArraySchema.safeParse(data);
  return result.success ? result.data : null;
}

export function validateControllers(data: unknown): Controller[] | null {
  const result = ControllersArraySchema.safeParse(data);
  return result.success ? result.data : null;
}

export function validateActionResult(data: unknown): ActionResult | null {
  const result = ActionResultSchema.safeParse(data);
  return result.success ? result.data : null;
}
