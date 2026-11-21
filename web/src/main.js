import '@fontsource/michroma/400.css'
import '@fontsource/saira/300.css'
import '@fontsource/saira/400.css'
import '@fontsource/saira/500.css'
import '@fontsource/jetbrains-mono/400.css'
import './app.css'
import { mount } from 'svelte'
import App from './App.svelte'

mount(App, { target: document.getElementById('app') })
