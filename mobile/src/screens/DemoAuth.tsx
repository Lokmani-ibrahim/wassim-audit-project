import React, { useState } from 'react';
import {
  Button,
  Copy,
  ErrorNotice,
  Field,
  Intro,
  Screen,
  Sheet,
  TextButton,
} from '../components/ui';
import type { Journey } from '../hooks/useJourney';
import { t } from '../locales';
import { authErrors } from '../utils/authValidation';
import type { DemoAuthInput } from '../types/domain';

export function DemoAuth({ journey: j, onDev }: { journey: Journey; onDev: () => void }) {
  const creating = j.route === 'createAccount';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [touched, setTouched] = useState<
    Partial<Record<'email' | 'password' | 'confirmPassword', boolean>>
  >({});
  const [lastFieldFocused, setLastFieldFocused] = useState(false);
  const [help, setHelp] = useState(false);
  const input: DemoAuthInput = {
    intent: creating ? 'create' : 'signIn',
    email,
    password,
    confirmPassword,
  };
  const errors = authErrors(input);
  const errorFor = (field: keyof typeof touched) =>
    touched[field] && errors[field] ? t(errors[field]) : undefined;
  const blur = (field: keyof typeof touched) =>
    setTouched((previous) => ({ ...previous, [field]: true }));
  return (
    <Screen
      brand
      onBrandLongPress={onDev}
      back={j.back}
      revealOnKeyboard={lastFieldFocused}
      footer={
        <>
          <ErrorNotice message={j.error && t(j.error)} />
          <Button
            label={t(creating ? 'createAccount' : 'signIn')}
            disabled={Object.keys(errors).length > 0}
            loading={!!j.busy}
            onPress={() => {
              void j.signIn(input);
            }}
          />
        </>
      }
    >
      <Intro
        title={t(creating ? 'createAccountTitle' : 'signInTitle')}
        body={t(creating ? 'createAccountBody' : 'signInBody')}
      />
      <Field
        label={t('email')}
        kind="email"
        value={email}
        onChangeText={setEmail}
        editable={!j.busy}
        error={errorFor('email')}
        onFocus={() => setLastFieldFocused(false)}
        onBlur={() => blur('email')}
      />
      <Field
        label={t('password')}
        kind="password"
        newPassword={creating}
        value={password}
        onChangeText={setPassword}
        editable={!j.busy}
        error={errorFor('password')}
        onFocus={() => setLastFieldFocused(!creating)}
        onBlur={() => blur('password')}
      />
      {creating && (
        <Field
          label={t('confirmPassword')}
          kind="password"
          newPassword
          value={confirmPassword}
          onChangeText={setConfirmPassword}
          editable={!j.busy}
          error={errorFor('confirmPassword')}
          onFocus={() => setLastFieldFocused(true)}
          onBlur={() => blur('confirmPassword')}
        />
      )}
      <Copy muted style={{ fontSize: 13 }}>
        {t('demoPasswordHint')}
      </Copy>
      {!creating && <TextButton label={t('forgotPassword')} onPress={() => setHelp(true)} />}
      <Sheet visible={help} title={t('passwordHelpTitle')} onClose={() => setHelp(false)}>
        <Copy>{t('passwordHelpBody')}</Copy>
        <Button label={t('gotIt')} onPress={() => setHelp(false)} />
      </Sheet>
    </Screen>
  );
}
