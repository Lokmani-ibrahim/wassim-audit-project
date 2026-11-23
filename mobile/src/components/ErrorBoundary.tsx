import React, { type ReactNode } from 'react';
import { Button, Intro, Screen } from './ui';
import { t } from '../locales';

export class ErrorBoundary extends React.Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <Screen
        brand
        footer={<Button label={t('retry')} onPress={() => this.setState({ failed: false })} />}
      >
        <Intro title={t('unexpectedError')} body={t('recoveryBody')} />
      </Screen>
    );
  }
}
