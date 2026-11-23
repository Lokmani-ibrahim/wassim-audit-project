import type { Services } from '../types/domain';
import { FeatureUnavailableError } from './errors';

// Used instead of the real transfer/history services when auth is mocked (no real token).
// They fail loudly with a typed error instead of fabricating data; screens show a
// "not available yet" state.
export const unavailableMoneyServices: Pick<Services, 'transfers' | 'transactions' | 'fx'> = {
  transfers: {
    async lookup() {
      throw new FeatureUnavailableError('transfers');
    },
    async send() {
      throw new FeatureUnavailableError('transfers');
    },
  },
  transactions: {
    async list() {
      throw new FeatureUnavailableError('transactions');
    },
  },
  fx: {
    async quote() {
      throw new FeatureUnavailableError('fx');
    },
    async convert() {
      throw new FeatureUnavailableError('fx');
    },
    async findByKey() {
      throw new FeatureUnavailableError('fx');
    },
  },
};
