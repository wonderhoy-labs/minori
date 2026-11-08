import { greet } from "@wonderhoy-labs/template";
import { brand } from "../brand";
import { AppBar, Footer } from "./Brand";

export function App() {
	return (
		<div className="app">
			<AppBar />
			<main className="hero">
				<h1>{greet(brand.name)}</h1>
				<p>{brand.tagline}</p>
				<mdui-card variant="filled" className="hero-card">
					<mdui-icon-rocket-launch />
					<span>
						Replace this page with your app. The colours, the bar above and the footer below come from the
						"wonderhoy" block in package.json. Add the project's logo yourself; agents never make one.
					</span>
				</mdui-card>
			</main>
			<Footer />
		</div>
	);
}
