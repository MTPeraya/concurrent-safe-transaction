export interface User {
  id: string;
  email: string;
  createdAt?: string;
}

export interface Account {
  id: string;
  ownerName: string;
  balance: number;
  owner?: User;
  createdAt: string;
  version?: number;
}

export interface Transaction {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: number;
  status: string;
  idempotencyKey?: string;
  createdAt: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
}
