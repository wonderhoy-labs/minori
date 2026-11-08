import "@fontsource-variable/roboto-flex";
import "mdui/mdui.css";
import "mdui/components/card.js";
import "@mdui/icons/rocket-launch.js";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./ui/App";
import "./ui/theme.css";
import "./ui/wonderhoy.css";
import "./ui/styles.css";

const root = document.getElementById("root");
if (!root) throw new Error("index.html has no #root");
createRoot(root).render(
	<StrictMode>
		<App />
	</StrictMode>,
);
